package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.Opcode;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import ru.bitcoin.node.script.SignatureHashType;
import ru.bitcoin.node.script.WitnessV0ScriptVerifier;
import ru.bitcoin.node.script.WitnessV0SignatureHash;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorP2shP2wpkhTest {

    private static final long UTXO_AMOUNT =
            200_000L;

    private static final int FLAGS =
            ScriptVerifyFlags.P2SH
                    | ScriptVerifyFlags.WITNESS;

    @Test
    void validP2shP2wpkhMustPass() {

        Fixture fixture =
                createFixture();

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    @Test
    void wrongUtxoAmountMustFail() {

        Fixture fixture =
                createFixture();

        UtxoEntry wrongUtxo =
                new UtxoEntry(
                        UTXO_AMOUNT + 1,
                        fixture.scriptPubKey(),
                        100,
                        false
                );

        UtxoView wrongView =
                outPoint ->
                        Optional.of(
                                wrongUtxo
                        );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                wrongView,
                                FLAGS
                        )
        );
    }

    @Test
    void modifiedWitnessSignatureMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] signature =
                oldInput.witness()
                        .item(0);

        signature[
                signature.length - 2
                ] ^= 0x01;

        Witness badWitness =
                new Witness(
                        List.of(
                                signature,
                                oldInput.witness()
                                        .item(1)
                        )
                );

        Transaction badTransaction =
                replaceInput(
                        fixture.transaction(),
                        new TxIn(
                                oldInput.previousOutput(),
                                oldInput.scriptSig(),
                                oldInput.sequence(),
                                badWitness
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    @Test
    void wrongRedeemScriptHashMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] scriptSig =
                oldInput.scriptSig();

        /*
         * Меняем один байт witness program,
         * поэтому HASH160(redeemScript)
         * больше не соответствует P2SH UTXO.
         */
        scriptSig[
                scriptSig.length - 1
                ] ^= 0x01;

        Transaction badTransaction =
                replaceInput(
                        fixture.transaction(),
                        new TxIn(
                                oldInput.previousOutput(),
                                scriptSig,
                                oldInput.sequence(),
                                oldInput.witness()
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    @Test
    void additionalPushInScriptSigMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] oldScriptSig =
                oldInput.scriptSig();

        byte[] badScriptSig =
                Arrays.copyOf(
                        oldScriptSig,
                        oldScriptSig.length + 2
                );

        /*
         * Добавляем:
         *
         * 01 01
         *
         * Теперь scriptSig уже не является
         * exact single push redeemScript.
         */
        badScriptSig[
                oldScriptSig.length
                ] =
                0x01;

        badScriptSig[
                oldScriptSig.length + 1
                ] =
                0x01;

        Transaction badTransaction =
                replaceInput(
                        fixture.transaction(),
                        new TxIn(
                                oldInput.previousOutput(),
                                badScriptSig,
                                oldInput.sequence(),
                                oldInput.witness()
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    @Test
    void pushData1WrappedWitnessMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] canonicalScriptSig =
                oldInput.scriptSig();

        /*
         * Первый байт canonical scriptSig —
         * direct push length.
         *
         * Остаток — redeemScript.
         */
        byte[] redeemScript =
                Arrays.copyOfRange(
                        canonicalScriptSig,
                        1,
                        canonicalScriptSig.length
                );

        byte[] badScriptSig =
                new byte[
                        2
                                + redeemScript.length
                        ];

        badScriptSig[0] =
                (byte)
                        Opcode.OP_PUSHDATA1;

        badScriptSig[1] =
                (byte)
                        redeemScript.length;

        System.arraycopy(
                redeemScript,
                0,
                badScriptSig,
                2,
                redeemScript.length
        );

        Transaction badTransaction =
                replaceInput(
                        fixture.transaction(),
                        new TxIn(
                                oldInput.previousOutput(),
                                badScriptSig,
                                oldInput.sequence(),
                                oldInput.witness()
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    @Test
    void emptyWitnessMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        Transaction badTransaction =
                replaceInput(
                        fixture.transaction(),
                        new TxIn(
                                oldInput.previousOutput(),
                                oldInput.scriptSig(),
                                oldInput.sequence(),
                                Witness.EMPTY
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                FLAGS
                        )
        );
    }

    private static Fixture createFixture() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] publicKeyBytes =
                publicKey.compressed();

        /*
         * P2WPKH witness program:
         *
         * OP_0
         * PUSH20
         * HASH160(pubkey)
         */
        byte[] publicKeyHash =
                Hash160.hash(
                        publicKeyBytes
                );

        byte[] redeemScript =
                new byte[22];

        redeemScript[0] =
                (byte)
                        Opcode.OP_0;

        redeemScript[1] =
                20;

        System.arraycopy(
                publicKeyHash,
                0,
                redeemScript,
                2,
                20
        );

        /*
         * P2SH scriptPubKey:
         *
         * OP_HASH160
         * PUSH20
         * HASH160(redeemScript)
         * OP_EQUAL
         */
        byte[] redeemScriptHash =
                Hash160.hash(
                        redeemScript
                );

        byte[] scriptPubKey =
                new byte[23];

        scriptPubKey[0] =
                (byte)
                        Opcode.OP_HASH160;

        scriptPubKey[1] =
                20;

        System.arraycopy(
                redeemScriptHash,
                0,
                scriptPubKey,
                2,
                20
        );

        scriptPubKey[22] =
                (byte)
                        Opcode.OP_EQUAL;

        /*
         * Nested SegWit scriptSig:
         *
         * ровно один direct push
         * redeemScript.
         *
         * redeemScript = 22 bytes,
         * поэтому первый byte = 0x16.
         */
        byte[] scriptSig =
                new byte[
                        1
                                + redeemScript.length
                        ];

        scriptSig[0] =
                (byte)
                        redeemScript.length;

        System.arraycopy(
                redeemScript,
                0,
                scriptSig,
                1,
                redeemScript.length
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                filled(
                                        32,
                                        (byte) 0x44
                                )
                        ),
                        new UInt32(0L)
                );

        TxIn unsignedInput =
                new TxIn(
                        previousOutput,
                        scriptSig,
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output =
                new TxOut(
                        180_000L,
                        new byte[]{
                                (byte)
                                        Opcode.OP_1
                        }
                );

        Transaction unsignedTransaction =
                new Transaction(
                        2,
                        List.of(
                                unsignedInput
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        /*
         * BIP143 P2WPKH scriptCode:
         *
         * DUP HASH160 PUSH20
         * <pubKeyHash>
         * EQUALVERIFY CHECKSIG
         */
        byte[] scriptCode =
                WitnessV0ScriptVerifier
                        .createP2wpkhScriptCode(
                                publicKeyHash
                        );

        byte[] digest =
                WitnessV0SignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        scriptCode,
                        UTXO_AMOUNT,
                        SignatureHashType.SIGHASH_ALL
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        byte[] der =
                signature.toDer();

        byte[] signatureWithHashType =
                Arrays.copyOf(
                        der,
                        der.length + 1
                );

        signatureWithHashType[
                signatureWithHashType.length - 1
                ] =
                (byte)
                        SignatureHashType.SIGHASH_ALL;

        Witness witness =
                new Witness(
                        List.of(
                                signatureWithHashType,
                                publicKeyBytes
                        )
                );

        TxIn signedInput =
                new TxIn(
                        previousOutput,
                        scriptSig,
                        new UInt32(
                                0xffff_fffeL
                        ),
                        witness
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                signedInput
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        UTXO_AMOUNT,
                        scriptPubKey,
                        100,
                        false
                );

        UtxoView view =
                outPoint -> {

                    if (previousOutput.equals(
                            outPoint
                    )) {
                        return Optional.of(
                                utxo
                        );
                    }

                    return Optional.empty();
                };

        return new Fixture(
                transaction,
                view,
                scriptPubKey
        );
    }

    private static Transaction replaceInput(
            Transaction transaction,
            TxIn input
    ) {
        return new Transaction(
                transaction.version(),
                List.of(
                        input
                ),
                transaction.outputs(),
                transaction.lockTime()
        );
    }

    private static byte[] filled(
            int length,
            byte value
    ) {
        byte[] result =
                new byte[length];

        Arrays.fill(
                result,
                value
        );

        return result;
    }

    private record Fixture(
            Transaction transaction,
            UtxoView utxoView,
            byte[] scriptPubKey
    ) {

        private Fixture {
            scriptPubKey =
                    scriptPubKey.clone();
        }

        @Override
        public byte[] scriptPubKey() {
            return scriptPubKey.clone();
        }
    }
}
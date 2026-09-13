package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.hash.Sha256;
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
import ru.bitcoin.node.script.WitnessV0SignatureHash;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorP2shP2wshTest {

    private static final long UTXO_AMOUNT =
            300_000L;

    private static final int FLAGS =
            ScriptVerifyFlags.P2SH
                    | ScriptVerifyFlags.WITNESS;

    @Test
    void validP2shP2wshMustPass() {

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
    void modifiedWitnessScriptMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] witnessScript =
                oldInput.witness()
                        .item(
                                oldInput.witness()
                                        .size() - 1
                        );

        witnessScript[0] ^= 0x01;

        Witness badWitness =
                new Witness(
                        List.of(
                                oldInput.witness()
                                        .item(0),
                                witnessScript
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
    void modifiedSignatureMustFail() {

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
         * Меняем байт внутри redeemScript.
         *
         * Теперь HASH160(redeemScript)
         * не соответствует P2SH scriptPubKey.
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
         * Добавляем ещё один push:
         *
         * 01 01
         *
         * Nested witness scriptSig обязан
         * содержать ровно один push redeemScript.
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
         * Первый byte canonical scriptSig —
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
         * witnessScript:
         *
         * <33-byte pubkey>
         * OP_CHECKSIG
         */
        byte[] witnessScript =
                new byte[
                        1
                                + publicKeyBytes.length
                                + 1
                        ];

        witnessScript[0] =
                (byte)
                        publicKeyBytes.length;

        System.arraycopy(
                publicKeyBytes,
                0,
                witnessScript,
                1,
                publicKeyBytes.length
        );

        witnessScript[
                witnessScript.length - 1
                ] =
                (byte)
                        Opcode.OP_CHECKSIG;

        /*
         * P2WSH redeemScript:
         *
         * OP_0
         * PUSH32
         * SHA256(witnessScript)
         */
        byte[] witnessScriptHash =
                Sha256.hash(
                        witnessScript
                );

        byte[] redeemScript =
                new byte[34];

        redeemScript[0] =
                (byte)
                        Opcode.OP_0;

        redeemScript[1] =
                32;

        System.arraycopy(
                witnessScriptHash,
                0,
                redeemScript,
                2,
                32
        );

        /*
         * Внешний P2SH:
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
         * scriptSig =
         * exact direct push redeemScript.
         *
         * redeemScript = 34 bytes,
         * поэтому первый байт = 0x22.
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
                                        (byte) 0x55
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
                        280_000L,
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
         * Для P2WSH BIP143 scriptCode =
         * witnessScript целиком.
         */
        byte[] digest =
                WitnessV0SignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        witnessScript,
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

        /*
         * P2WSH witness:
         *
         * [signature]
         * [witnessScript]
         */
        Witness witness =
                new Witness(
                        List.of(
                                signatureWithHashType,
                                witnessScript
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
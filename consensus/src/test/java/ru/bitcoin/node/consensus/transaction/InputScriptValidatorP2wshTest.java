package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
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
import ru.bitcoin.node.script.WitnessProgram;
import ru.bitcoin.node.script.WitnessV0SignatureHash;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorP2wshTest {

    private static final long UTXO_AMOUNT =
            200_000L;

    @Test
    void validNativeP2wshMustPass() {

        Fixture fixture =
                createFixture();

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void wrongAmountMustFail() {

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
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void modifiedWitnessScriptMustFailHashCheck() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        byte[] modifiedScript =
                oldInput.witness()
                        .item(
                                oldInput.witness()
                                        .size() - 1
                        );

        modifiedScript[0] ^= 0x01;

        Witness badWitness =
                new Witness(
                        List.of(
                                oldInput.witness()
                                        .item(0),
                                modifiedScript
                        )
                );

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        oldInput.scriptSig(),
                        oldInput.sequence(),
                        badWitness
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
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

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        oldInput.scriptSig(),
                        oldInput.sequence(),
                        badWitness
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void nonEmptyNativeScriptSigMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        new byte[]{0x00},
                        oldInput.sequence(),
                        oldInput.witness()
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
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

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        oldInput.scriptSig(),
                        oldInput.sequence(),
                        Witness.EMPTY
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                badTransaction,
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
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
         * <33-byte compressed pubkey>
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

        byte[] witnessScriptHash =
                Sha256.hash(
                        witnessScript
                );

        byte[] scriptPubKey =
                p2wshScriptPubKey(
                        witnessScriptHash
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        scriptPubKey
                ).orElseThrow();

        if (!witnessProgram.isP2wsh()) {
            throw new IllegalStateException(
                    "Expected P2WSH witness program"
            );
        }

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                filled(
                                        32,
                                        (byte) 0x33
                                )
                        ),
                        new UInt32(0L)
                );

        TxIn unsignedInput =
                new TxIn(
                        previousOutput,
                        new byte[0],
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
         * Для P2WSH scriptCode =
         * сам witnessScript.
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
                        new byte[0],
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

    private static byte[] p2wshScriptPubKey(
            byte[] witnessScriptHash
    ) {

        if (witnessScriptHash.length != 32) {
            throw new IllegalArgumentException(
                    "witnessScriptHash must contain 32 bytes"
            );
        }

        byte[] result =
                new byte[34];

        result[0] =
                (byte)
                        Opcode.OP_0;

        result[1] =
                32;

        System.arraycopy(
                witnessScriptHash,
                0,
                result,
                2,
                32
        );

        return result;
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
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
import ru.bitcoin.node.script.WitnessV0SignatureHash;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorP2wshMultiSigTest {

    private static final long UTXO_AMOUNT =
            500_000L;

    private static final int FLAGS =
            ScriptVerifyFlags.WITNESS
                    | ScriptVerifyFlags.NULLDUMMY;

    @Test
    void validTwoOfThreeP2wshMultisigMustPass() {

        Fixture fixture =
                createFixture(
                        new byte[0],
                        false
                );

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
    void nonEmptyDummyWithNullDummyMustFail() {

        Fixture fixture =
                createFixture(
                        new byte[]{
                                0x01
                        },
                        false
                );

        assertThrows(
                TransactionValidationException.class,
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
    void reversedSignaturesMustFail() {

        Fixture fixture =
                createFixture(
                        new byte[0],
                        true
                );

        assertThrows(
                TransactionValidationException.class,
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
                createFixture(
                        new byte[0],
                        false
                );

        UtxoEntry wrongUtxo =
                new UtxoEntry(
                        UTXO_AMOUNT + 1,
                        fixture.scriptPubKey(),
                        100,
                        false
                );

        UtxoView wrongView =
                outPoint -> {

                    if (fixture.previousOutput()
                            .equals(outPoint)) {

                        return Optional.of(
                                wrongUtxo
                        );
                    }

                    return Optional.empty();
                };

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
                createFixture(
                        new byte[0],
                        false
                );

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        Witness oldWitness =
                oldInput.witness();

        byte[] modifiedWitnessScript =
                oldWitness.item(
                        oldWitness.size() - 1
                );

        /*
         * Меняем один байт witnessScript.
         *
         * SHA256 больше не будет соответствовать
         * P2WSH witness program из UTXO.
         */
        modifiedWitnessScript[1] ^= 0x01;

        Witness badWitness =
                new Witness(
                        List.of(
                                oldWitness.item(0),
                                oldWitness.item(1),
                                oldWitness.item(2),
                                modifiedWitnessScript
                        )
                );

        Transaction badTransaction =
                replaceWitness(
                        fixture.transaction(),
                        badWitness
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
    void unexpectedScriptSigForNativeP2wshMustFail() {

        Fixture fixture =
                createFixture(
                        new byte[0],
                        false
                );

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        new byte[]{
                                0x00
                        },
                        oldInput.sequence(),
                        oldInput.witness()
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(
                                badInput
                        ),
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
                                FLAGS
                        )
        );
    }

    private static Fixture createFixture(
            byte[] dummy,
            boolean reverseSignatures
    ) {

        /*
         * Три независимых private key.
         */
        PrivateKey key1 =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey key2 =
                new PrivateKey(
                        BigInteger.TWO
                );

        PrivateKey key3 =
                new PrivateKey(
                        BigInteger.valueOf(3)
                );

        PublicKey pub1 =
                Secp256k1.publicKey(
                        key1
                );

        PublicKey pub2 =
                Secp256k1.publicKey(
                        key2
                );

        PublicKey pub3 =
                Secp256k1.publicKey(
                        key3
                );

        /*
         * witnessScript:
         *
         * OP_2
         * <pubkey1>
         * <pubkey2>
         * <pubkey3>
         * OP_3
         * OP_CHECKMULTISIG
         */
        byte[] witnessScript =
                createTwoOfThreeWitnessScript(
                        pub1.compressed(),
                        pub2.compressed(),
                        pub3.compressed()
                );

        /*
         * Native P2WSH scriptPubKey:
         *
         * OP_0
         * PUSH32
         * SHA256(witnessScript)
         */
        byte[] witnessScriptHash =
                Sha256.hash(
                        witnessScript
                );

        byte[] scriptPubKey =
                new byte[34];

        scriptPubKey[0] =
                (byte)
                        Opcode.OP_0;

        scriptPubKey[1] =
                32;

        System.arraycopy(
                witnessScriptHash,
                0,
                scriptPubKey,
                2,
                32
        );

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x77
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0L)
                );

        /*
         * Для native witness scriptSig обязан
         * быть пустым.
         */
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
                        480_000L,
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
         * BIP143:
         *
         * для P2WSH scriptCode =
         * witnessScript целиком.
         *
         * Обе подписи подписывают
         * один и тот же digest.
         */
        byte[] digest =
                WitnessV0SignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        witnessScript,
                        UTXO_AMOUNT,
                        SignatureHashType.SIGHASH_ALL
                );

        byte[] signature1 =
                signDigest(
                        digest,
                        key1
                );

        /*
         * Вторая подпись принадлежит key3.
         *
         * pubkey2 пропускается.
         *
         * Это проверяет последовательный
         * CHECKMULTISIG matching:
         *
         * sig1 -> pub1
         *
         * sig3 -> pub2 = false
         * sig3 -> pub3 = true
         */
        byte[] signature3 =
                signDigest(
                        digest,
                        key3
                );

        List<byte[]> witnessItems;

        if (reverseSignatures) {

            /*
             * Невалидный порядок:
             *
             * dummy
             * sig3
             * sig1
             * witnessScript
             */
            witnessItems =
                    List.of(
                            dummy,
                            signature3,
                            signature1,
                            witnessScript
                    );

        } else {

            /*
             * Правильный witness:
             *
             * 0          historical dummy
             * sig1
             * sig3
             * witnessScript
             */
            witnessItems =
                    List.of(
                            dummy,
                            signature1,
                            signature3,
                            witnessScript
                    );
        }

        Witness witness =
                new Witness(
                        witnessItems
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
                scriptPubKey,
                previousOutput
        );
    }

    private static byte[] createTwoOfThreeWitnessScript(
            byte[] pub1,
            byte[] pub2,
            byte[] pub3
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                Opcode.OP_2
        );

        writeDirectPush(
                out,
                pub1
        );

        writeDirectPush(
                out,
                pub2
        );

        writeDirectPush(
                out,
                pub3
        );

        out.write(
                Opcode.OP_3
        );

        out.write(
                Opcode.OP_CHECKMULTISIG
        );

        return out.toByteArray();
    }

    private static void writeDirectPush(
            ByteArrayOutputStream out,
            byte[] data
    ) {

        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }

        if (data.length < 1
                || data.length > 75) {

            throw new IllegalArgumentException(
                    "Direct push requires data length 1..75"
            );
        }

        out.write(
                data.length
        );

        out.writeBytes(
                data
        );
    }

    private static byte[] signDigest(
            byte[] digest,
            PrivateKey privateKey
    ) {

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        byte[] der =
                signature.toDer();

        byte[] result =
                Arrays.copyOf(
                        der,
                        der.length + 1
                );

        result[
                result.length - 1
                ] =
                (byte)
                        SignatureHashType.SIGHASH_ALL;

        return result;
    }

    private static Transaction replaceWitness(
            Transaction transaction,
            Witness witness
    ) {

        TxIn oldInput =
                transaction.inputs()
                        .getFirst();

        TxIn newInput =
                new TxIn(
                        oldInput.previousOutput(),
                        oldInput.scriptSig(),
                        oldInput.sequence(),
                        witness
                );

        return new Transaction(
                transaction.version(),
                List.of(
                        newInput
                ),
                transaction.outputs(),
                transaction.lockTime()
        );
    }

    private record Fixture(
            Transaction transaction,
            UtxoView utxoView,
            byte[] scriptPubKey,
            OutPoint previousOutput
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
package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptNullFailTest {

    private static final int NULLFAIL =
            ScriptVerifyFlags.NULLFAIL;

    @Test
    void checkSigInvalidNonEmptySignatureMustFailWithNullFail() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        differentKey
                ).compressed();

        byte[] script =
                checkSigScript(
                        publicKey,
                        Opcode.OP_CHECKSIG
                );

        Transaction transaction =
                transaction();

        /*
         * Подписываем правильный sighash,
         * но ДРУГИМ private key.
         *
         * DER корректный,
         * подпись непустая,
         * криптографическая проверка должна дать false.
         */
        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );
    }

    @Test
    void checkSigInvalidNonEmptySignatureWithoutNullFailMustReturnFalse() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        differentKey
                ).compressed();

        byte[] script =
                checkSigScript(
                        publicKey,
                        Opcode.OP_CHECKSIG
                );

        Transaction transaction =
                transaction();

        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NONE
                );

        ScriptInterpreter.execute(
                script,
                machine,
                context
        );

        assertEquals(
                1,
                machine.size()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkSigEmptySignatureMustRemainFalseWithNullFail() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        privateKey
                ).compressed();

        byte[] script =
                checkSigScript(
                        publicKey,
                        Opcode.OP_CHECKSIG
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction(),
                        0,
                        script,
                        NULLFAIL
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );

        assertEquals(
                1,
                machine.size()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkSigValidSignatureMustPassWithNullFail() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        privateKey
                ).compressed();

        byte[] script =
                checkSigScript(
                        publicKey,
                        Opcode.OP_CHECKSIG
                );

        Transaction transaction =
                transaction();

        byte[] signature =
                sign(
                        transaction,
                        script,
                        privateKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                signature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        ScriptInterpreter.execute(
                script,
                machine,
                context
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkSigVerifyInvalidNonEmptySignatureMustFailWithNullFail() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        differentKey
                ).compressed();

        byte[] script =
                checkSigScript(
                        publicKey,
                        Opcode.OP_CHECKSIGVERIFY
                );

        Transaction transaction =
                transaction();

        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );
    }

    @Test
    void checkMultiSigInvalidNonEmptySignatureMustFailWithNullFail() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        PublicKey expectedPublicKey =
                Secp256k1.publicKey(
                        differentKey
                );

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                expectedPublicKey.compressed()
                        ),
                        Opcode.OP_CHECKMULTISIG
                );

        Transaction transaction =
                transaction();

        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Historical CHECKMULTISIG dummy.
         */
        machine.push(
                new byte[0]
        );

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );
    }

    @Test
    void checkMultiSigInvalidNonEmptySignatureWithoutNullFailMustReturnFalse() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                Secp256k1.publicKey(
                                        differentKey
                                ).compressed()
                        ),
                        Opcode.OP_CHECKMULTISIG
                );

        Transaction transaction =
                transaction();

        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NONE
                );

        ScriptInterpreter.execute(
                script,
                machine,
                context
        );

        assertEquals(
                1,
                machine.size()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkMultiSigEmptySignatureMustRemainFalseWithNullFail() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                Secp256k1.publicKey(
                                        privateKey
                                ).compressed()
                        ),
                        Opcode.OP_CHECKMULTISIG
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        /*
         * M = 1, но signature пустая.
         *
         * NULLFAIL разрешает именно empty signature.
         */
        machine.push(
                new byte[0]
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction(),
                        0,
                        script,
                        NULLFAIL
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );

        assertEquals(
                1,
                machine.size()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkMultiSigValidSignatureMustPassWithNullFail() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        privateKey
                ).compressed();

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                publicKey
                        ),
                        Opcode.OP_CHECKMULTISIG
                );

        Transaction transaction =
                transaction();

        byte[] signature =
                sign(
                        transaction,
                        script,
                        privateKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                signature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        ScriptInterpreter.execute(
                script,
                machine,
                context
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void checkMultiSigMustRejectWhenAnyProvidedSignatureIsNonEmptyOnFailure() {

        PrivateKey key1 =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey key2 =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] script =
                multisigScript(
                        2,
                        List.of(
                                Secp256k1.publicKey(
                                        key1
                                ).compressed(),
                                Secp256k1.publicKey(
                                        key2
                                ).compressed()
                        ),
                        Opcode.OP_CHECKMULTISIG
                );

        Transaction transaction =
                transaction();

        /*
         * Первая signature пустая.
         * Вторая непустая, но подписана key1,
         * поэтому весь 2-of-2 не может успешно пройти.
         *
         * Для NULLFAIL недостаточно, чтобы только
         * "неудачная" signature была пустой.
         *
         * При false должны быть пустыми ВСЕ
         * signatures операции.
         */
        byte[] nonEmptySignature =
                sign(
                        transaction,
                        script,
                        key1
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                new byte[0]
        );

        machine.push(
                nonEmptySignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );
    }

    @Test
    void checkMultiSigVerifyInvalidNonEmptySignatureMustFailWithNullFail() {

        PrivateKey signingKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PrivateKey differentKey =
                new PrivateKey(
                        BigInteger.TWO
                );

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                Secp256k1.publicKey(
                                        differentKey
                                ).compressed()
                        ),
                        Opcode.OP_CHECKMULTISIGVERIFY
                );

        Transaction transaction =
                transaction();

        byte[] invalidSignature =
                sign(
                        transaction,
                        script,
                        signingKey
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                invalidSignature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        NULLFAIL
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );
    }

    private static byte[] checkSigScript(
            byte[] publicKey,
            int opcode
    ) {

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        pushData(
                output,
                publicKey
        );

        output.write(
                opcode
        );

        return output.toByteArray();
    }

    private static byte[] multisigScript(
            int requiredSignatures,
            List<byte[]> publicKeys,
            int finalOpcode
    ) {

        if (requiredSignatures < 0
                || requiredSignatures > 16) {

            throw new IllegalArgumentException(
                    "Unsupported signature count"
            );
        }

        if (publicKeys.isEmpty()
                || publicKeys.size() > 16) {

            throw new IllegalArgumentException(
                    "Unsupported public key count"
            );
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        output.write(
                smallIntegerOpcode(
                        requiredSignatures
                )
        );

        for (byte[] publicKey : publicKeys) {

            pushData(
                    output,
                    publicKey
            );
        }

        output.write(
                smallIntegerOpcode(
                        publicKeys.size()
                )
        );

        output.write(
                finalOpcode
        );

        return output.toByteArray();
    }

    private static int smallIntegerOpcode(
            int value
    ) {

        if (value == 0) {
            return Opcode.OP_0;
        }

        if (value < 1
                || value > 16) {

            throw new IllegalArgumentException(
                    "Small integer opcode out of range"
            );
        }

        return Opcode.OP_1
                + value
                - 1;
    }

    private static void pushData(
            ByteArrayOutputStream output,
            byte[] data
    ) {

        if (data.length
                > Opcode.OP_DATA_MAX) {

            throw new IllegalArgumentException(
                    "Test helper supports only direct pushes"
            );
        }

        output.write(
                data.length
        );

        output.writeBytes(
                data
        );
    }

    private static byte[] sign(
            Transaction transaction,
            byte[] script,
            PrivateKey privateKey
    ) {

        byte[] digest =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        script,
                        SignatureHashType.SIGHASH_ALL
                );

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

    private static Transaction transaction() {

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x55
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0L)
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output =
                new TxOut(
                        490_000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        return new Transaction(
                2,
                List.of(
                        input
                ),
                List.of(
                        output
                ),
                new UInt32(0L)
        );
    }
}
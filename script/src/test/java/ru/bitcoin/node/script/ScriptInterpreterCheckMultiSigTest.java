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

class ScriptInterpreterCheckMultiSigTest {

    private static final long AMOUNT =
            500_000L;

    @Test
    void oneOfOneMustPass() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] script =
                multisigScript(
                        1,
                        List.of(
                                publicKey.compressed()
                        )
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

        /*
         * Historical CHECKMULTISIG dummy.
         */
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

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void twoOfThreeMustPass() {

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
                Secp256k1.publicKey(key1);

        PublicKey pub2 =
                Secp256k1.publicKey(key2);

        PublicKey pub3 =
                Secp256k1.publicKey(key3);

        byte[] script =
                multisigScript(
                        2,
                        List.of(
                                pub1.compressed(),
                                pub2.compressed(),
                                pub3.compressed()
                        )
                );

        Transaction transaction =
                transaction();

        /*
         * Подписывают key1 и key3.
         *
         * CHECKMULTISIG допускает пропуск pub2:
         *
         * sig1 -> pub1
         * sig3 -> pub2 FAIL
         * sig3 -> pub3 OK
         */
        byte[] signature1 =
                sign(
                        transaction,
                        script,
                        key1
                );

        byte[] signature3 =
                sign(
                        transaction,
                        script,
                        key3
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                signature1
        );

        machine.push(
                signature3
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

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void signaturesInWrongOrderMustFail() {

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
                Secp256k1.publicKey(key1);

        PublicKey pub2 =
                Secp256k1.publicKey(key2);

        PublicKey pub3 =
                Secp256k1.publicKey(key3);

        byte[] script =
                multisigScript(
                        2,
                        List.of(
                                pub1.compressed(),
                                pub2.compressed(),
                                pub3.compressed()
                        )
                );

        Transaction transaction =
                transaction();

        byte[] signature1 =
                sign(
                        transaction,
                        script,
                        key1
                );

        byte[] signature3 =
                sign(
                        transaction,
                        script,
                        key3
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        /*
         * Нарочно меняем порядок:
         *
         * sig3 идёт раньше sig1.
         *
         * CHECKMULTISIG не может вернуться
         * назад по списку pubkeys.
         */
        machine.push(
                signature3
        );

        machine.push(
                signature1
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
    void insufficientSignaturesMustFailExecution() {

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

        byte[] script =
                multisigScript(
                        2,
                        List.of(
                                Secp256k1.publicKey(
                                        key1
                                ).compressed(),
                                Secp256k1.publicKey(
                                        key2
                                ).compressed(),
                                Secp256k1.publicKey(
                                        key3
                                ).compressed()
                        )
                );

        Transaction transaction =
                transaction();

        byte[] signature1 =
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

        /*
         * Script требует 2 signatures,
         * но предоставлена только одна.
         */
        machine.push(
                signature1
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NONE
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
    void nonEmptyDummyWithoutNullDummyFlagMustPass() {

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
                        )
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

        /*
         * Исторически без NULLDUMMY
         * dummy мог быть непустым.
         */
        machine.push(
                new byte[]{
                        0x01
                }
        );

        machine.push(
                signature
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

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void nonEmptyDummyWithNullDummyFlagMustFail() {

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
                        )
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
                new byte[]{
                        0x01
                }
        );

        machine.push(
                signature
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NULLDUMMY
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
    void emptyDummyWithNullDummyFlagMustPass() {

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
                        )
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
                        ScriptVerifyFlags.NULLDUMMY
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
    void checkMultiSigVerifyMustConsumeSuccessfulResult() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        byte[] publicKey =
                Secp256k1.publicKey(
                        privateKey
                ).compressed();

        byte[] script =
                multisigVerifyScript(
                        1,
                        List.of(
                                publicKey
                        )
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
                        ScriptVerifyFlags.NONE
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );

        /*
         * CHECKMULTISIGVERIFY сначала получает
         * true от CHECKMULTISIG,
         * а затем VERIFY удаляет его.
         */
        assertTrue(
                machine.isEmpty()
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

        byte[] hash =
                new byte[32];

        Arrays.fill(
                hash,
                (byte) 0x66
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(hash),
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
                        AMOUNT - 10_000L,
                        new byte[]{
                                (byte)
                                        Opcode.OP_1
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

    private static byte[] multisigScript(
            int requiredSignatures,
            List<byte[]> publicKeys
    ) {
        return createMultisigScript(
                requiredSignatures,
                publicKeys,
                Opcode.OP_CHECKMULTISIG
        );
    }

    private static byte[] multisigVerifyScript(
            int requiredSignatures,
            List<byte[]> publicKeys
    ) {
        return createMultisigScript(
                requiredSignatures,
                publicKeys,
                Opcode.OP_CHECKMULTISIGVERIFY
        );
    }

    private static byte[] createMultisigScript(
            int requiredSignatures,
            List<byte[]> publicKeys,
            int finalOpcode
    ) {

        if (requiredSignatures < 0
                || requiredSignatures
                > ScriptLimits.MAX_PUBKEYS_PER_MULTISIG) {

            throw new IllegalArgumentException(
                    "requiredSignatures must be in range 0.."
                            + ScriptLimits.MAX_PUBKEYS_PER_MULTISIG
            );
        }

        if (publicKeys == null
                || publicKeys.isEmpty()
                || publicKeys.size()
                > ScriptLimits.MAX_PUBKEYS_PER_MULTISIG) {

            throw new IllegalArgumentException(
                    "publicKeys size must be in range 1.."
                            + ScriptLimits.MAX_PUBKEYS_PER_MULTISIG
            );
        }

        if (requiredSignatures
                > publicKeys.size()) {

            throw new IllegalArgumentException(
                    "requiredSignatures must not exceed public key count"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        writeScriptNumber(
                out,
                requiredSignatures
        );

        for (byte[] publicKey
                : publicKeys) {

            out.write(
                    publicKey.length
            );

            out.writeBytes(
                    publicKey
            );
        }

        writeScriptNumber(
                out,
                publicKeys.size()
        );

        out.write(
                finalOpcode
        );

        return out.toByteArray();
    }

    private static void writeScriptNumber(
            ByteArrayOutputStream out,
            int value
    ) {
        if (value < 0
                || value
                > ScriptLimits.MAX_PUBKEYS_PER_MULTISIG) {

            throw new IllegalArgumentException(
                    "value must be in range 0.."
                            + ScriptLimits.MAX_PUBKEYS_PER_MULTISIG
            );
        }

        if (value == 0) {

            out.write(
                    Opcode.OP_0
            );

            return;
        }

        if (value <= 16) {

            out.write(
                    Opcode.OP_1
                            + value
                            - 1
            );

            return;
        }

        /*
         * 17..20:
         *
         * минимальный ScriptNumber занимает 1 byte.
         *
         * Например 20:
         *
         * 01 14
         *
         * 01 = push 1 byte
         * 14 = decimal 20
         */
        byte[] encoded =
                ScriptNumber.encode(
                        value
                );

        out.write(
                encoded.length
        );

        out.writeBytes(
                encoded
        );
    }

    private static byte[] prependCodeSeparators(
            byte[] script,
            int count
    ) {

        byte[] result =
                new byte[
                        count + script.length
                        ];

        Arrays.fill(
                result,
                0,
                count,
                (byte)
                        Opcode.OP_CODESEPARATOR
        );

        System.arraycopy(
                script,
                0,
                result,
                count,
                script.length
        );

        return result;
    }
    @Test
    void checkMultiSigAdditionalOpCountAt201MustPass() {

        List<byte[]> publicKeys =
                java.util.stream.IntStream
                        .rangeClosed(1, 20)
                        .mapToObj(
                                i ->
                                        Secp256k1.publicKey(
                                                new PrivateKey(
                                                        BigInteger.valueOf(i)
                                                )
                                        ).compressed()
                        )
                        .toList();

        byte[] multisig =
                createMultisigScript(
                        0,
                        publicKeys,
                        Opcode.OP_CHECKMULTISIG
                );

        /*
         * 180 OP_CODESEPARATOR
         * + 1 OP_CHECKMULTISIG
         * + N=20
         *
         * = 201.
         */
        byte[] script =
                prependCodeSeparators(
                        multisig,
                        180
                );

        Transaction transaction =
                transaction();

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 0-of-20 CHECKMULTISIG всё равно
         * потребляет historical dummy.
         */
        machine.push(
                new byte[0]
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NONE
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context
                        )
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }
    @Test
    void checkMultiSigAdditionalOpCountAt202MustFail() {

        List<byte[]> publicKeys =
                java.util.stream.IntStream
                        .rangeClosed(1, 20)
                        .mapToObj(
                                i ->
                                        Secp256k1.publicKey(
                                                new PrivateKey(
                                                        BigInteger.valueOf(i)
                                                )
                                        ).compressed()
                        )
                        .toList();

        byte[] multisig =
                createMultisigScript(
                        0,
                        publicKeys,
                        Opcode.OP_CHECKMULTISIG
                );

        /*
         * 181
         * + 1 CHECKMULTISIG
         * + 20 pubkeys
         *
         * = 202.
         */
        byte[] script =
                prependCodeSeparators(
                        multisig,
                        181
                );

        Transaction transaction =
                transaction();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        ScriptVerifyFlags.NONE
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
        /*
         * Проверка op-count должна произойти
         * сразу после чтения N через peek().
         *
         * Поэтому historical dummy остаётся
         * на stack и CHECKMULTISIG не начинает
         * разбирать остальные аргументы.
         */
        /*
         * До OP_CHECKMULTISIG script уже выполнил:
         *
         * OP_0                -> M = 0
         * 20 public key pushes
         * push ScriptNumber 20 -> N = 20
         *
         * Плюс historical dummy был на stack заранее.
         *
         * Итого:
         *
         * dummy
         * M
         * 20 pubkeys
         * N
         *
         * = 23 элемента.
         *
         * CHECKMULTISIG обязан обнаружить превышение
         * op-count после чтения N через peek(),
         * но ДО pop() и дальнейшего разбора аргументов.
         */
        assertEquals(
                23,
                machine.size()
        );

        /*
         * Верхний элемент всё ещё N = 20.
         * Значит CHECKMULTISIG не успел сделать pop().
         */
        assertArrayEquals(
                ScriptNumber.encode(20),
                machine.peek()
        );
    }
}
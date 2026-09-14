package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConstScriptCodeExecutionTest {

    @Test
    void legacyCheckSigWithoutConstScriptCodeMustAllowFindAndDelete() {

        byte[] signature =
                exampleSignature();

        byte[] publicKey =
                examplePublicKey();

        byte[] scriptCode =
                scriptCodeContainingSignature(
                        signature
                );

        ScriptMachine machine =
                checkSigMachine(
                        signature,
                        publicKey
                );

        ScriptExecutionContext context =
                context(
                        scriptCode,
                        ScriptVerifyFlags.NONE,
                        SignatureVersion.LEGACY
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKSIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void legacyCheckSigWithConstScriptCodeMustRejectFindAndDelete() {

        byte[] signature =
                exampleSignature();

        byte[] publicKey =
                examplePublicKey();

        byte[] scriptCode =
                scriptCodeContainingSignature(
                        signature
                );

        ScriptMachine machine =
                checkSigMachine(
                        signature,
                        publicKey
                );

        ScriptExecutionContext context =
                context(
                        scriptCode,
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        SignatureVersion.LEGACY
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKSIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void legacyCheckSigWithConstScriptCodeMustPassWhenSignatureIsAbsentFromScriptCode() {

        byte[] signature =
                exampleSignature();

        ScriptMachine machine =
                checkSigMachine(
                        signature,
                        examplePublicKey()
                );

        byte[] scriptCode = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_DROP
        };

        ScriptExecutionContext context =
                context(
                        scriptCode,
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        SignatureVersion.LEGACY
                );

        /*
         * CHECKSIG может вернуть false из-за того,
         * что fixture signature не является настоящей
         * подписью transaction.
         *
         * Нам важно только отсутствие Script error
         * от CONST_SCRIPTCODE.
         */
        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKSIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void witnessV0CheckSigMustNotUseLegacyFindAndDelete() {

        byte[] signature =
                exampleSignature();

        byte[] scriptCode =
                scriptCodeContainingSignature(
                        signature
                );

        ScriptMachine machine =
                checkSigMachine(
                        signature,
                        examplePublicKey()
                );

        ScriptExecutionContext context =
                context(
                        scriptCode,
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        SignatureVersion.WITNESS_V0
                );

        /*
         * BIP143/WITNESS_V0 не выполняет legacy
         * FindAndDelete, поэтому наличие signature
         * внутри scriptCode само по себе не является
         * CONST_SCRIPTCODE failure.
         */
        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKSIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void legacyCheckMultiSigWithoutConstScriptCodeMustAllowFindAndDelete() {

        byte[] signature =
                exampleSignature();

        ScriptMachine machine =
                checkMultiSigMachine(
                        signature,
                        examplePublicKey()
                );

        ScriptExecutionContext context =
                context(
                        scriptCodeContainingSignature(
                                signature
                        ),
                        ScriptVerifyFlags.NONE,
                        SignatureVersion.LEGACY
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKMULTISIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void legacyCheckMultiSigWithConstScriptCodeMustRejectFindAndDelete() {

        byte[] signature =
                exampleSignature();

        ScriptMachine machine =
                checkMultiSigMachine(
                        signature,
                        examplePublicKey()
                );

        ScriptExecutionContext context =
                context(
                        scriptCodeContainingSignature(
                                signature
                        ),
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        SignatureVersion.LEGACY
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKMULTISIG
                                },
                                machine,
                                context
                        )
        );
    }

    @Test
    void witnessV0CheckMultiSigMustNotUseLegacyFindAndDelete() {

        byte[] signature =
                exampleSignature();

        ScriptMachine machine =
                checkMultiSigMachine(
                        signature,
                        examplePublicKey()
                );

        ScriptExecutionContext context =
                context(
                        scriptCodeContainingSignature(
                                signature
                        ),
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        SignatureVersion.WITNESS_V0
                );

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                new byte[]{
                                        (byte) Opcode.OP_CHECKMULTISIG
                                },
                                machine,
                                context
                        )
        );
    }

    private static ScriptMachine checkSigMachine(
            byte[] signature,
            byte[] publicKey
    ) {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Stack перед CHECKSIG:
         *
         * <signature>
         * <pubkey>
         *
         * pubkey находится на вершине.
         */
        machine.push(
                signature
        );

        machine.push(
                publicKey
        );

        return machine;
    }

    private static ScriptMachine checkMultiSigMachine(
            byte[] signature,
            byte[] publicKey
    ) {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Stack layout перед 1-of-1 CHECKMULTISIG:
         *
         * <dummy>
         * <signature>
         * <M = 1>
         * <pubkey>
         * <N = 1>
         *
         * Верхушка = N.
         */

        machine.push(
                new byte[0]
        );

        machine.push(
                signature
        );

        machine.push(
                ScriptNumber.encode(1)
        );

        machine.push(
                publicKey
        );

        machine.push(
                ScriptNumber.encode(1)
        );

        return machine;
    }

    private static ScriptExecutionContext context(
            byte[] scriptCode,
            int flags,
            SignatureVersion signatureVersion
    ) {

        return new ScriptExecutionContext(
                transaction(),
                0,
                scriptCode,
                flags,
                100_000L,
                signatureVersion
        );
    }

    private static byte[] scriptCodeContainingSignature(
            byte[] signature
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        /*
         * Некоторые opcodes вокруг signature нужны,
         * чтобы мы точно видели, что FindAndDelete
         * удаляет только serialized push signature.
         */
        out.write(
                Opcode.OP_1
        );

        writeDirectPush(
                out,
                signature
        );

        out.write(
                Opcode.OP_DROP
        );

        return out.toByteArray();
    }

    private static void writeDirectPush(
            ByteArrayOutputStream out,
            byte[] data
    ) {

        if (data.length < 1
                || data.length > 75) {

            throw new IllegalArgumentException(
                    "Direct push requires 1..75 bytes"
            );
        }

        out.write(
                data.length
        );

        out.writeBytes(
                data
        );
    }

    private static byte[] exampleSignature() {

        /*
         * Структурно корректная DER transaction signature:
         *
         * 30 06
         * 02 01 01
         * 02 01 01
         * 01 = SIGHASH_ALL
         *
         * Она не обязана криптографически
         * подписывать fixture transaction.
         */
        return new byte[]{
                0x30,
                0x06,
                0x02,
                0x01,
                0x01,
                0x02,
                0x01,
                0x01,
                0x01
        };
    }

    private static byte[] examplePublicKey() {

        /*
         * Формально compressed-shaped public key.
         *
         * Даже если точка окажется невалидной,
         * PublicKey.fromBytes() даст обычный
         * CHECKSIG false.
         *
         * Для CONST_SCRIPTCODE тестируется
         * более ранняя FindAndDelete branch.
         */
        byte[] publicKey =
                new byte[33];

        publicKey[0] =
                0x02;

        Arrays.fill(
                publicKey,
                1,
                publicKey.length,
                (byte) 0x11
        );

        return publicKey;
    }

    private static Transaction transaction() {

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x55
        );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                new Hash256(
                                        previousHash
                                ),
                                new UInt32(0L)
                        ),
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output =
                new TxOut(
                        90_000L,
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
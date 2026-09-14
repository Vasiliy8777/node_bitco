package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstScriptCodeTest {

    @Test
    void findAndDeleteMustModifyScriptWhenSignaturePushExists() {

        byte[] signature =
                exampleSignature();

        byte[] script =
                scriptContainingSignature(
                        signature
                );

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script,
                        signature
                );

        assertFalse(
                Arrays.equals(
                        script,
                        result
                )
        );
    }

    @Test
    void findAndDeleteMustNotModifyScriptWhenSignatureIsAbsent() {

        byte[] signature =
                exampleSignature();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_ADD
        };

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script,
                        signature
                );

        assertArrayEquals(
                script,
                result
        );
    }

    @Test
    void serializedSignaturePushMustBeCompletelyRemoved() {

        byte[] signature =
                exampleSignature();

        byte[] script =
                scriptContainingSignature(
                        signature
                );

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script,
                        signature
                );

        /*
         * script:
         *
         * OP_1
         * <signature>
         * OP_2
         *
         * FindAndDelete удаляет именно push signature,
         * поэтому остаётся:
         *
         * OP_1 OP_2
         */
        assertArrayEquals(
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_2
                },
                result
        );
    }

    @Test
    void sameSignaturePushMustBeRemovedEverywhere() {

        byte[] signature =
                exampleSignature();

        ByteArrayOutputStream script =
                new ByteArrayOutputStream();

        script.write(
                Opcode.OP_1
        );

        writeDirectPush(
                script,
                signature
        );

        script.write(
                Opcode.OP_2
        );

        writeDirectPush(
                script,
                signature
        );

        script.write(
                Opcode.OP_3
        );

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script.toByteArray(),
                        signature
                );

        assertArrayEquals(
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_2,
                        (byte) Opcode.OP_3
                },
                result
        );
    }

    @Test
    void constScriptCodeFlagMustExistIndependently() {

        assertTrue(
                ScriptVerifyFlags.has(
                        ScriptVerifyFlags.CONST_SCRIPTCODE,
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
        );

        assertFalse(
                ScriptVerifyFlags.has(
                        ScriptVerifyFlags.NONE,
                        ScriptVerifyFlags.CONST_SCRIPTCODE
                )
        );
    }

    private static byte[] scriptContainingSignature(
            byte[] signature
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                Opcode.OP_1
        );

        writeDirectPush(
                out,
                signature
        );

        out.write(
                Opcode.OP_2
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
         * DER:
         *
         * 30 06
         * 02 01 01
         * 02 01 01
         *
         * + SIGHASH_ALL
         *
         * Для этих тестов криптографическая
         * валидность подписи не важна.
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
}
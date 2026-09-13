package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LegacyScriptCodeTest {

    @Test
    void shouldReturnScriptAfterCodeSeparatorOffset() {

        byte[] script =
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_CODESEPARATOR,
                        (byte) Opcode.OP_2,
                        (byte) Opcode.OP_CHECKSIG
                };

        /*
         * Offset 2 =
         * сразу после OP_CODESEPARATOR.
         */
        byte[] result =
                LegacyScriptCode.afterCodeSeparator(
                        script,
                        2
                );

        assertArrayEquals(
                new byte[]{
                        (byte) Opcode.OP_2,
                        (byte) Opcode.OP_CHECKSIG
                },
                result
        );
    }

    @Test
    void findAndDeleteShouldRemoveExactSignaturePush() {

        byte[] signature =
                new byte[]{
                        0x11,
                        0x22
                };

        /*
         * 02 11 22
         * OP_1
         * CHECKSIG
         */
        byte[] script =
                new byte[]{
                        0x02,
                        0x11,
                        0x22,
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_CHECKSIG
                };

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script,
                        signature
                );

        assertArrayEquals(
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_CHECKSIG
                },
                result
        );
    }

    @Test
    void findAndDeleteShouldRemoveRepeatedExactPushes() {

        byte[] signature =
                new byte[]{
                        0x11,
                        0x22
                };

        byte[] script =
                new byte[]{
                        0x02,
                        0x11,
                        0x22,

                        0x02,
                        0x11,
                        0x22,

                        (byte) Opcode.OP_CHECKSIG
                };

        byte[] result =
                LegacyScriptCode.findAndDeleteSignature(
                        script,
                        signature
                );

        assertArrayEquals(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                result
        );
    }

    @Test
    void mustNotDeleteSignatureBytesInsideLargerPush() {

        byte[] signature =
                new byte[]{
                        0x11,
                        0x22
                };

        /*
         * Push FOUR bytes:
         *
         * 04 11 22 33 44
         *
         * Хотя payload начинается с signature,
         * это НЕ exact serialized push.
         */
        byte[] script =
                new byte[]{
                        0x04,
                        0x11,
                        0x22,
                        0x33,
                        0x44,

                        (byte) Opcode.OP_CHECKSIG
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
    void mustNotDeleteNonMinimalPushEncoding() {

        byte[] signature =
                new byte[]{
                        0x11,
                        0x22
                };

        /*
         * Minimal serialized signature push был бы:
         *
         * 02 11 22
         *
         * Здесь используется:
         *
         * OP_PUSHDATA1 02 11 22
         *
         * Поэтому exact FindAndDelete совпадения нет.
         */
        byte[] script =
                new byte[]{
                        (byte) Opcode.OP_PUSHDATA1,
                        0x02,
                        0x11,
                        0x22,

                        (byte) Opcode.OP_CHECKSIG
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
    void signaturePatternInsidePushDataMustNotBeTreatedAsOpcodeBoundary() {

        byte[] signature =
                new byte[]{
                        0x11,
                        0x22
                };

        /*
         * push 5 bytes:
         *
         * aa 02 11 22 bb
         *
         * Внутри payload встречается:
         *
         * 02 11 22
         *
         * но это не opcode boundary.
         */
        byte[] script =
                new byte[]{
                        0x05,
                        (byte) 0xaa,
                        0x02,
                        0x11,
                        0x22,
                        (byte) 0xbb,

                        (byte) Opcode.OP_CHECKSIG
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
}
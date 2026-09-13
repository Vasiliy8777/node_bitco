package ru.bitcoin.node.script;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class LegacyScriptCode {

    private LegacyScriptCode() {
    }

    public static byte[] afterCodeSeparator(
            byte[] script,
            int offset
    ) {
        if (script == null) {
            throw new IllegalArgumentException(
                    "script must not be null"
            );
        }

        if (offset < 0
                || offset > script.length) {

            throw new IllegalArgumentException(
                    "code separator offset is out of range: "
                            + offset
            );
        }

        return Arrays.copyOfRange(
                script,
                offset,
                script.length
        );
    }

    public static byte[] findAndDeleteSignature(
            byte[] scriptCode,
            byte[] signatureWithHashType
    ) {
        if (scriptCode == null) {
            throw new IllegalArgumentException(
                    "scriptCode must not be null"
            );
        }

        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        byte[] serializedSignaturePush =
                serializeMinimalPush(
                        signatureWithHashType
                );

        if (serializedSignaturePush.length == 0
                || scriptCode.length == 0) {

            return scriptCode.clone();
        }

        ByteArrayOutputStream result =
                new ByteArrayOutputStream(
                        scriptCode.length
                );

        int position = 0;

        while (position < scriptCode.length) {

            /*
             * Bitcoin Core FindAndDelete проверяет
             * совпадение только начиная с границы
             * текущего opcode.
             */
            while (matchesAt(
                    scriptCode,
                    position,
                    serializedSignaturePush
            )) {
                position +=
                        serializedSignaturePush.length;

                if (position
                        >= scriptCode.length) {

                    break;
                }
            }

            if (position
                    >= scriptCode.length) {

                break;
            }

            int instructionEnd =
                    nextInstructionOffset(
                            scriptCode,
                            position
                    );

            result.writeBytes(
                    Arrays.copyOfRange(
                            scriptCode,
                            position,
                            instructionEnd
                    )
            );

            position =
                    instructionEnd;
        }

        return result.toByteArray();
    }

    private static boolean matchesAt(
            byte[] script,
            int offset,
            byte[] target
    ) {
        if (offset < 0
                || target.length
                > script.length - offset) {

            return false;
        }

        for (int i = 0;
             i < target.length;
             i++) {

            if (script[offset + i]
                    != target[i]) {

                return false;
            }
        }

        return true;
    }

    private static byte[] serializeMinimalPush(
            byte[] data
    ) {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(
                        data.length + 5
                );

        if (data.length == 0) {

            out.write(
                    Opcode.OP_0
            );

        } else if (data.length
                <= Opcode.OP_DATA_MAX) {

            out.write(
                    data.length
            );

        } else if (data.length
                <= 0xff) {

            out.write(
                    Opcode.OP_PUSHDATA1
            );

            out.write(
                    data.length
            );

        } else if (data.length
                <= 0xffff) {

            out.write(
                    Opcode.OP_PUSHDATA2
            );

            out.write(
                    data.length
                            & 0xff
            );

            out.write(
                    (data.length >>> 8)
                            & 0xff
            );

        } else {

            out.write(
                    Opcode.OP_PUSHDATA4
            );

            out.write(
                    data.length
                            & 0xff
            );

            out.write(
                    (data.length >>> 8)
                            & 0xff
            );

            out.write(
                    (data.length >>> 16)
                            & 0xff
            );

            out.write(
                    (data.length >>> 24)
                            & 0xff
            );
        }

        out.writeBytes(data);

        return out.toByteArray();
    }

    private static int nextInstructionOffset(
            byte[] script,
            int position
    ) {
        if (position < 0
                || position >= script.length) {

            throw new ScriptParseException(
                    "Invalid script position"
            );
        }

        int opcode =
                Byte.toUnsignedInt(
                        script[position]
                );

        int cursor =
                position + 1;

        if (opcode >= Opcode.OP_DATA_MIN
                && opcode <= Opcode.OP_DATA_MAX) {

            return requirePayload(
                    script,
                    cursor,
                    opcode
            );
        }

        if (opcode == Opcode.OP_PUSHDATA1) {

            requireAvailable(
                    script,
                    cursor,
                    1
            );

            int length =
                    Byte.toUnsignedInt(
                            script[cursor]
                    );

            cursor++;

            return requirePayload(
                    script,
                    cursor,
                    length
            );
        }

        if (opcode == Opcode.OP_PUSHDATA2) {

            requireAvailable(
                    script,
                    cursor,
                    2
            );

            int length =
                    Byte.toUnsignedInt(
                            script[cursor]
                    )
                            |
                            (
                                    Byte.toUnsignedInt(
                                            script[cursor + 1]
                                    )
                                            << 8
                            );

            cursor += 2;

            return requirePayload(
                    script,
                    cursor,
                    length
            );
        }

        if (opcode == Opcode.OP_PUSHDATA4) {

            requireAvailable(
                    script,
                    cursor,
                    4
            );

            long length =
                    Integer.toUnsignedLong(
                            Byte.toUnsignedInt(
                                    script[cursor]
                            )
                                    |
                                    (
                                            Byte.toUnsignedInt(
                                                    script[cursor + 1]
                                            )
                                                    << 8
                                    )
                                    |
                                    (
                                            Byte.toUnsignedInt(
                                                    script[cursor + 2]
                                            )
                                                    << 16
                                    )
                                    |
                                    (
                                            Byte.toUnsignedInt(
                                                    script[cursor + 3]
                                            )
                                                    << 24
                                    )
                    );

            if (length > Integer.MAX_VALUE) {

                throw new ScriptParseException(
                        "OP_PUSHDATA4 length is too large"
                );
            }

            cursor += 4;

            return requirePayload(
                    script,
                    cursor,
                    (int) length
            );
        }

        return cursor;
    }

    private static int requirePayload(
            byte[] script,
            int offset,
            int length
    ) {
        requireAvailable(
                script,
                offset,
                length
        );

        return offset + length;
    }

    private static void requireAvailable(
            byte[] script,
            int offset,
            int length
    ) {
        if (offset < 0
                || length < 0
                || offset > script.length
                || length > script.length - offset) {

            throw new ScriptParseException(
                    "Truncated script"
            );
        }
    }
}
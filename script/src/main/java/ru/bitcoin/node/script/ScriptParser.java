package ru.bitcoin.node.script;

import java.util.ArrayList;
import java.util.List;

public final class ScriptParser {

    private ScriptParser() {
    }

    public static List<ScriptInstruction> parse(
            byte[] script
    ) {
        if (script == null) {
            throw new IllegalArgumentException(
                    "script must not be null"
            );
        }

        List<ScriptInstruction> result =
                new ArrayList<>();

        int position = 0;

        while (position < script.length) {

            int opcode =
                    Byte.toUnsignedInt(
                            script[position]
                    );

            position++;

            if (opcode == Opcode.OP_0) {
                result.add(
                        ScriptInstruction.push(
                                opcode,
                                new byte[0]
                        )
                );
                continue;
            }

            if (Opcode.isDirectPush(opcode)) {

                PushResult push =
                        readPush(
                                script,
                                position,
                                opcode
                        );

                result.add(
                        ScriptInstruction.push(
                                opcode,
                                push.data()
                        )
                );

                position =
                        push.nextPosition();

                continue;
            }

            if (opcode == Opcode.OP_PUSHDATA1) {

                requireAvailable(
                        script,
                        position,
                        1,
                        "OP_PUSHDATA1 length"
                );

                int length =
                        Byte.toUnsignedInt(
                                script[position]
                        );

                position++;

                PushResult push =
                        readPush(
                                script,
                                position,
                                length
                        );

                result.add(
                        ScriptInstruction.push(
                                opcode,
                                push.data()
                        )
                );

                position =
                        push.nextPosition();

                continue;
            }

            if (opcode == Opcode.OP_PUSHDATA2) {

                requireAvailable(
                        script,
                        position,
                        2,
                        "OP_PUSHDATA2 length"
                );

                int length =
                        Byte.toUnsignedInt(
                                script[position]
                        )
                                |
                                (
                                        Byte.toUnsignedInt(
                                                script[position + 1]
                                        )
                                                << 8
                                );

                position += 2;

                PushResult push =
                        readPush(
                                script,
                                position,
                                length
                        );

                result.add(
                        ScriptInstruction.push(
                                opcode,
                                push.data()
                        )
                );

                position =
                        push.nextPosition();

                continue;
            }

            if (opcode == Opcode.OP_PUSHDATA4) {

                requireAvailable(
                        script,
                        position,
                        4,
                        "OP_PUSHDATA4 length"
                );

                long length =
                        Integer.toUnsignedLong(
                                readLittleEndianInt(
                                        script,
                                        position
                                )
                        );

                position += 4;

                if (length > Integer.MAX_VALUE) {
                    throw new ScriptParseException(
                            "OP_PUSHDATA4 length exceeds "
                                    + "Java array limit: "
                                    + length
                    );
                }

                PushResult push =
                        readPush(
                                script,
                                position,
                                (int) length
                        );

                result.add(
                        ScriptInstruction.push(
                                opcode,
                                push.data()
                        )
                );

                position =
                        push.nextPosition();

                continue;
            }

            result.add(
                    ScriptInstruction.opcode(
                            opcode
                    )
            );
        }

        return List.copyOf(
                result
        );
    }

    private static PushResult readPush(
            byte[] script,
            int position,
            int length
    ) {
        if (length < 0) {
            throw new ScriptParseException(
                    "Negative push length"
            );
        }

        requireAvailable(
                script,
                position,
                length,
                "push data"
        );

        byte[] data =
                new byte[length];

        System.arraycopy(
                script,
                position,
                data,
                0,
                length
        );

        return new PushResult(
                data,
                position + length
        );
    }

    private static void requireAvailable(
            byte[] script,
            int position,
            int required,
            String description
    ) {
        if (position < 0
                || required < 0
                || position > script.length
                || required > script.length - position) {

            throw new ScriptParseException(
                    "Truncated "
                            + description
                            + " at position "
                            + position
            );
        }
    }

    private static int readLittleEndianInt(
            byte[] bytes,
            int offset
    ) {
        return Byte.toUnsignedInt(
                bytes[offset]
        )
                |
                (
                        Byte.toUnsignedInt(
                                bytes[offset + 1]
                        )
                                << 8
                )
                |
                (
                        Byte.toUnsignedInt(
                                bytes[offset + 2]
                        )
                                << 16
                )
                |
                (
                        Byte.toUnsignedInt(
                                bytes[offset + 3]
                        )
                                << 24
                );
    }

    private record PushResult(
            byte[] data,
            int nextPosition
    ) {
    }
}
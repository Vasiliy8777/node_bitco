package ru.bitcoin.node.script;

import java.util.Arrays;

public final class ScriptInstruction {

    private final int opcode;
    private final byte[] data;

    private ScriptInstruction(
            int opcode,
            byte[] data
    ) {
        if (opcode < 0 || opcode > 0xFF) {
            throw new IllegalArgumentException(
                    "opcode must be in range 0..255"
            );
        }

        this.opcode = opcode;
        this.data =
                data == null
                        ? null
                        : Arrays.copyOf(
                        data,
                        data.length
                );
    }

    public static ScriptInstruction opcode(
            int opcode
    ) {
        return new ScriptInstruction(
                opcode,
                null
        );
    }

    public static ScriptInstruction push(
            int opcode,
            byte[] data
    ) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }

        return new ScriptInstruction(
                opcode,
                data
        );
    }

    public int opcode() {
        return opcode;
    }

    public boolean isPushData() {
        return data != null
                || opcode == Opcode.OP_0;
    }

    public byte[] data() {
        if (data == null) {
            return null;
        }

        return Arrays.copyOf(
                data,
                data.length
        );
    }

    public int dataLength() {
        return data == null
                ? 0
                : data.length;
    }
}
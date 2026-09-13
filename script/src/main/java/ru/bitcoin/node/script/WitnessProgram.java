package ru.bitcoin.node.script;

import java.util.Arrays;
import java.util.Optional;

public final class WitnessProgram {

    public static final int MIN_PROGRAM_LENGTH = 2;
    public static final int MAX_PROGRAM_LENGTH = 40;

    private final int version;
    private final byte[] program;

    private WitnessProgram(
            int version,
            byte[] program
    ) {
        this.version = version;
        this.program = program.clone();
    }

    public int version() {
        return version;
    }

    public byte[] program() {
        return program.clone();
    }

    public int programLength() {
        return program.length;
    }

    public boolean isVersionZero() {
        return version == 0;
    }

    public boolean isP2wpkh() {
        return version == 0
                && program.length == 20;
    }

    public boolean isP2wsh() {
        return version == 0
                && program.length == 32;
    }

    /*
     * BIP141 witness program:
     *
     *   OP_0..OP_16 <2..40 byte program>
     *
     * ВАЖНО:
     * program должен быть pushed непосредственно
     * одним canonical direct-push opcode.
     *
     * Поэтому:
     *
     * OP_0 0x14 <20 bytes>     -> witness program
     *
     * OP_0 OP_PUSHDATA1 0x14
     * <20 bytes>               -> НЕ witness program.
     */
    public static Optional<WitnessProgram> parse(
            byte[] scriptPubKey
    ) {
        if (scriptPubKey == null) {
            throw new IllegalArgumentException(
                    "scriptPubKey must not be null"
            );
        }

        /*
         * Минимум:
         *
         * version opcode
         * length opcode
         * 2 bytes program
         *
         * = 4 bytes
         *
         * Максимум:
         *
         * version opcode
         * length opcode
         * 40 bytes program
         *
         * = 42 bytes
         */
        if (scriptPubKey.length
                < 2 + MIN_PROGRAM_LENGTH
                || scriptPubKey.length
                > 2 + MAX_PROGRAM_LENGTH) {

            return Optional.empty();
        }

        int versionOpcode =
                Byte.toUnsignedInt(
                        scriptPubKey[0]
                );

        int version;

        if (versionOpcode == Opcode.OP_0) {

            version = 0;

        } else if (versionOpcode >= Opcode.OP_1
                && versionOpcode <= Opcode.OP_16) {

            version =
                    versionOpcode
                            - Opcode.OP_1
                            + 1;

        } else {

            return Optional.empty();
        }

        int programLength =
                Byte.toUnsignedInt(
                        scriptPubKey[1]
                );

        /*
         * BIP141:
         *
         * witness program length must be 2..40.
         */
        if (programLength < MIN_PROGRAM_LENGTH
                || programLength > MAX_PROGRAM_LENGTH) {

            return Optional.empty();
        }

        /*
         * Второй byte должен быть именно direct push
         * длины witness program.
         *
         * Поскольку допустимая длина <= 40,
         * значение opcode одновременно равно длине.
         */
        if (scriptPubKey.length
                != 2 + programLength) {

            return Optional.empty();
        }

        byte[] program =
                Arrays.copyOfRange(
                        scriptPubKey,
                        2,
                        scriptPubKey.length
                );

        return Optional.of(
                new WitnessProgram(
                        version,
                        program
                )
        );
    }
}
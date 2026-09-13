package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WitnessProgramTest {

    @Test
    void shouldRecognizeVersionZeroP2wpkh() {

        byte[] program =
                filled(
                        20,
                        (byte) 0x11
                );

        byte[] script =
                witnessScript(
                        Opcode.OP_0,
                        program
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(script)
                        .orElseThrow();

        assertEquals(
                0,
                witnessProgram.version()
        );

        assertEquals(
                20,
                witnessProgram.programLength()
        );

        assertArrayEquals(
                program,
                witnessProgram.program()
        );

        assertTrue(
                witnessProgram.isVersionZero()
        );

        assertTrue(
                witnessProgram.isP2wpkh()
        );

        assertFalse(
                witnessProgram.isP2wsh()
        );
    }

    @Test
    void shouldRecognizeVersionZeroP2wsh() {

        byte[] program =
                filled(
                        32,
                        (byte) 0x22
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_0,
                                program
                        )
                ).orElseThrow();

        assertEquals(
                0,
                witnessProgram.version()
        );

        assertTrue(
                witnessProgram.isP2wsh()
        );

        assertFalse(
                witnessProgram.isP2wpkh()
        );
    }

    @Test
    void shouldRecognizeMinimumProgramLength() {

        byte[] program =
                filled(
                        2,
                        (byte) 0x33
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_0,
                                program
                        )
                ).orElseThrow();

        assertEquals(
                2,
                witnessProgram.programLength()
        );
    }

    @Test
    void shouldRecognizeMaximumProgramLength() {

        byte[] program =
                filled(
                        40,
                        (byte) 0x44
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_16,
                                program
                        )
                ).orElseThrow();

        assertEquals(
                16,
                witnessProgram.version()
        );

        assertEquals(
                40,
                witnessProgram.programLength()
        );
    }

    @Test
    void shouldRecognizeAllWitnessVersions() {

        for (int version = 0;
             version <= 16;
             version++) {

            int opcode =
                    version == 0
                            ? Opcode.OP_0
                            : Opcode.OP_1
                            + version
                            - 1;

            WitnessProgram witnessProgram =
                    WitnessProgram.parse(
                            witnessScript(
                                    opcode,
                                    filled(
                                            20,
                                            (byte) version
                                    )
                            )
                    ).orElseThrow();

            assertEquals(
                    version,
                    witnessProgram.version()
            );
        }
    }

    @Test
    void shouldRejectProgramShorterThanTwoBytes() {

        assertTrue(
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_0,
                                new byte[]{0x01}
                        )
                ).isEmpty()
        );
    }

    @Test
    void shouldRejectProgramLongerThanFortyBytes() {

        assertTrue(
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_0,
                                filled(
                                        41,
                                        (byte) 0x55
                                )
                        )
                ).isEmpty()
        );
    }

    @Test
    void shouldRejectInvalidVersionOpcode() {

        byte[] script =
                witnessScript(
                        Opcode.OP_DUP,
                        filled(
                                20,
                                (byte) 0x66
                        )
                );

        assertTrue(
                WitnessProgram.parse(script)
                        .isEmpty()
        );
    }

    @Test
    void shouldRejectDeclaredLengthDifferentFromActualLength() {

        byte[] script =
                new byte[22];

        script[0] =
                (byte) Opcode.OP_0;

        /*
         * Declares 19 bytes,
         * but 20 bytes actually follow.
         */
        script[1] =
                19;

        Arrays.fill(
                script,
                2,
                script.length,
                (byte) 0x77
        );

        assertTrue(
                WitnessProgram.parse(script)
                        .isEmpty()
        );
    }

    @Test
    void shouldRejectPushData1Encoding() {

        byte[] program =
                filled(
                        20,
                        (byte) 0x11
                );

        byte[] script =
                new byte[
                        3 + program.length
                        ];

        script[0] =
                (byte) Opcode.OP_0;

        script[1] =
                (byte) Opcode.OP_PUSHDATA1;

        script[2] =
                (byte) program.length;

        System.arraycopy(
                program,
                0,
                script,
                3,
                program.length
        );

        assertTrue(
                WitnessProgram.parse(script)
                        .isEmpty()
        );
    }

    @Test
    void shouldRejectExtraTrailingByte() {

        byte[] program =
                filled(
                        20,
                        (byte) 0x22
                );

        byte[] normal =
                witnessScript(
                        Opcode.OP_0,
                        program
                );

        byte[] script =
                Arrays.copyOf(
                        normal,
                        normal.length + 1
                );

        script[
                script.length - 1
                ] =
                (byte) Opcode.OP_1;

        assertTrue(
                WitnessProgram.parse(script)
                        .isEmpty()
        );
    }

    @Test
    void shouldDefensivelyCopyProgram() {

        byte[] original =
                filled(
                        20,
                        (byte) 0x33
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        witnessScript(
                                Opcode.OP_0,
                                original
                        )
                ).orElseThrow();

        byte[] returned =
                witnessProgram.program();

        returned[0] =
                (byte) 0x7f;

        assertEquals(
                (byte) 0x33,
                witnessProgram.program()[0]
        );
    }

    private static byte[] witnessScript(
            int versionOpcode,
            byte[] program
    ) {
        byte[] result =
                new byte[
                        2 + program.length
                        ];

        result[0] =
                (byte) versionOpcode;

        result[1] =
                (byte) program.length;

        System.arraycopy(
                program,
                0,
                result,
                2,
                program.length
        );

        return result;
    }

    private static byte[] filled(
            int length,
            byte value
    ) {
        byte[] result =
                new byte[length];

        Arrays.fill(
                result,
                value
        );

        return result;
    }
}
package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptParserTest {

    @Test
    void shouldParseOrdinaryOpcode() {

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_DUP,
                                (byte) Opcode.OP_HASH160
                        }
                );

        assertEquals(
                2,
                instructions.size()
        );

        assertEquals(
                Opcode.OP_DUP,
                instructions.get(0)
                        .opcode()
        );

        assertFalse(
                instructions.get(0)
                        .isPushData()
        );

        assertEquals(
                Opcode.OP_HASH160,
                instructions.get(1)
                        .opcode()
        );
    }

    @Test
    void shouldParseOpZeroAsEmptyPush() {

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_0
                        }
                );

        assertEquals(
                1,
                instructions.size()
        );

        ScriptInstruction instruction =
                instructions.get(0);

        assertTrue(
                instruction.isPushData()
        );

        assertEquals(
                0,
                instruction.dataLength()
        );

        assertArrayEquals(
                new byte[0],
                instruction.data()
        );
    }

    @Test
    void shouldParseDirectPush() {

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        new byte[]{
                                0x03,
                                0x11,
                                0x22,
                                0x33
                        }
                );

        assertEquals(
                1,
                instructions.size()
        );

        ScriptInstruction instruction =
                instructions.get(0);

        assertEquals(
                3,
                instruction.opcode()
        );

        assertArrayEquals(
                new byte[]{
                        0x11,
                        0x22,
                        0x33
                },
                instruction.data()
        );
    }

    @Test
    void shouldParsePushData1() {

        byte[] script =
                new byte[1 + 1 + 76];

        script[0] =
                (byte) Opcode.OP_PUSHDATA1;

        script[1] =
                76;

        for (int i = 0; i < 76; i++) {
            script[i + 2] =
                    (byte) i;
        }

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        assertEquals(
                1,
                instructions.size()
        );

        assertEquals(
                Opcode.OP_PUSHDATA1,
                instructions.get(0)
                        .opcode()
        );

        assertEquals(
                76,
                instructions.get(0)
                        .dataLength()
        );
    }

    @Test
    void shouldParsePushData2LittleEndianLength() {

        int length = 256;

        byte[] script =
                new byte[
                        1 + 2 + length
                        ];

        script[0] =
                (byte) Opcode.OP_PUSHDATA2;

        script[1] =
                0x00;

        script[2] =
                0x01;

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        assertEquals(
                1,
                instructions.size()
        );

        assertEquals(
                length,
                instructions.get(0)
                        .dataLength()
        );
    }

    @Test
    void shouldParsePushData4LittleEndianLength() {

        int length = 256;

        byte[] script =
                new byte[
                        1 + 4 + length
                        ];

        script[0] =
                (byte) Opcode.OP_PUSHDATA4;

        script[1] =
                0x00;

        script[2] =
                0x01;

        script[3] =
                0x00;

        script[4] =
                0x00;

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        assertEquals(
                1,
                instructions.size()
        );

        assertEquals(
                length,
                instructions.get(0)
                        .dataLength()
        );
    }

    @Test
    void shouldPreserveNonMinimalPushEncoding() {

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_PUSHDATA1,
                                0x03,
                                0x11,
                                0x22,
                                0x33
                        }
                );

        assertEquals(
                Opcode.OP_PUSHDATA1,
                instructions.get(0)
                        .opcode()
        );

        assertArrayEquals(
                new byte[]{
                        0x11,
                        0x22,
                        0x33
                },
                instructions.get(0)
                        .data()
        );
    }

    @Test
    void shouldRejectTruncatedDirectPush() {

        assertThrows(
                ScriptParseException.class,
                () -> ScriptParser.parse(
                        new byte[]{
                                0x03,
                                0x11,
                                0x22
                        }
                )
        );
    }

    @Test
    void shouldRejectMissingPushData1Length() {

        assertThrows(
                ScriptParseException.class,
                () -> ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_PUSHDATA1
                        }
                )
        );
    }

    @Test
    void shouldRejectTruncatedPushData2() {

        assertThrows(
                ScriptParseException.class,
                () -> ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_PUSHDATA2,
                                0x03,
                                0x00,
                                0x11
                        }
                )
        );
    }

    @Test
    void shouldReturnImmutableInstructionList() {

        List<ScriptInstruction> instructions =
                ScriptParser.parse(
                        new byte[]{
                                (byte) Opcode.OP_DUP
                        }
                );

        assertThrows(
                UnsupportedOperationException.class,
                () -> instructions.add(
                        ScriptInstruction.opcode(
                                Opcode.OP_DROP
                        )
                )
        );
    }

    @Test
    void instructionMustDefensivelyCopyPushData() {

        byte[] script =
                new byte[]{
                        0x01,
                        0x42
                };

        List<ScriptInstruction> instructions =
                ScriptParser.parse(script);

        script[1] =
                0x55;

        assertArrayEquals(
                new byte[]{
                        0x42
                },
                instructions.get(0)
                        .data()
        );

        byte[] returned =
                instructions.get(0)
                        .data();

        returned[0] =
                0x66;

        assertArrayEquals(
                new byte[]{
                        0x42
                },
                instructions.get(0)
                        .data()
        );
    }
}
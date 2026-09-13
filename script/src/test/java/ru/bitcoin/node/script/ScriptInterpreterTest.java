package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.hash.Hash160;

import static org.junit.jupiter.api.Assertions.*;

class ScriptInterpreterTest {

    @Test
    void shouldPushDataOntoStack() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x03,
                        0x11,
                        0x22,
                        0x33
                },
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{
                        0x11,
                        0x22,
                        0x33
                },
                machine.pop()
        );
    }

    @Test
    void opZeroShouldPushEmptyVector() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_0
                },
                machine
        );

        assertArrayEquals(
                new byte[0],
                machine.pop()
        );
    }

    @Test
    void smallIntegerOpcodesShouldPushScriptNumbers() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_16
                },
                machine
        );

        assertArrayEquals(
                ScriptNumber.encode(16),
                machine.pop()
        );

        assertArrayEquals(
                ScriptNumber.encode(1),
                machine.pop()
        );
    }

    @Test
    void opOneNegateShouldPushMinusOne() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_1NEGATE
                },
                machine
        );

        assertArrayEquals(
                new byte[]{
                        (byte) 0x81
                },
                machine.pop()
        );
    }

    @Test
    void opDupShouldDuplicateTopElement() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x01,
                        0x42,
                        (byte) Opcode.OP_DUP
                },
                machine
        );

        assertEquals(
                2,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{0x42},
                machine.pop()
        );

        assertArrayEquals(
                new byte[]{0x42},
                machine.pop()
        );
    }

    @Test
    void opDropShouldRemoveTopElement() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x01,
                        0x11,
                        0x01,
                        0x22,
                        (byte) Opcode.OP_DROP
                },
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{0x11},
                machine.pop()
        );
    }

    @Test
    void opEqualShouldPushTrueForEqualValues() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x01,
                        0x42,
                        0x01,
                        0x42,
                        (byte) Opcode.OP_EQUAL
                },
                machine
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void opEqualShouldPushFalseForDifferentValues() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x01,
                        0x42,
                        0x01,
                        0x43,
                        (byte) Opcode.OP_EQUAL
                },
                machine
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void opEqualVerifyShouldConsumeSuccessfulResult() {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                new byte[]{
                        0x01,
                        0x42,
                        0x01,
                        0x42,
                        (byte) Opcode.OP_EQUALVERIFY
                },
                machine
        );

        assertTrue(
                machine.isEmpty()
        );
    }

    @Test
    void opEqualVerifyShouldRejectDifferentValues() {

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                0x01,
                                0x42,
                                0x01,
                                0x43,
                                (byte) Opcode.OP_EQUALVERIFY
                        },
                        machine
                )
        );
    }

    @Test
    void opVerifyShouldAcceptTrue() {

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_1,
                                (byte) Opcode.OP_VERIFY
                        },
                        machine
                )
        );

        assertTrue(
                machine.isEmpty()
        );
    }

    @Test
    void opVerifyShouldRejectFalse() {

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_0,
                                (byte) Opcode.OP_VERIFY
                        },
                        machine
                )
        );
    }

    @Test
    void negativeZeroMustBeFalse() {

        assertFalse(
                ScriptNumber.castToBool(
                        new byte[]{
                                (byte) 0x80
                        }
                )
        );

        assertFalse(
                ScriptNumber.castToBool(
                        new byte[]{
                                0x00,
                                (byte) 0x80
                        }
                )
        );

        assertFalse(
                ScriptNumber.castToBool(
                        new byte[]{
                                0x00,
                                0x00,
                                (byte) 0x80
                        }
                )
        );
    }

    @Test
    void ordinaryNonZeroValueMustBeTrue() {

        assertTrue(
                ScriptNumber.castToBool(
                        new byte[]{
                                0x00,
                                0x01
                        }
                )
        );
    }

    @Test
    void opHash160ShouldHashTopElement() {

        byte[] value =
                new byte[]{
                        0x11,
                        0x22,
                        0x33
                };

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(value);

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_HASH160
                },
                machine
        );

        assertArrayEquals(
                Hash160.hash(value),
                machine.pop()
        );
    }

    @Test
    void stackUnderflowShouldFail() {

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_DUP
                        },
                        machine
                )
        );
    }

    @Test
    void unsupportedOpcodeShouldFail() {

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_RETURN
                        },
                        machine
                )
        );
    }

    @Test
    void machineMustDefensivelyCopyStackElements() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] original =
                new byte[]{0x42};

        machine.push(original);

        original[0] =
                0x55;

        byte[] returned =
                machine.peek();

        returned[0] =
                0x66;

        assertArrayEquals(
                new byte[]{0x42},
                machine.peek()
        );
    }
}
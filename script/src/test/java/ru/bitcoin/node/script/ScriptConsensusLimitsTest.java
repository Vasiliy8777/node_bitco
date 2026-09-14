package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ScriptConsensusLimitsTest {

    @Test
    void scriptOfExactlyTenThousandBytesMustPassSizeLimit() {

        /*
         * 19 × OP_PUSHDATA2(520 bytes):
         *
         * 19 × (1 opcode + 2 length bytes + 520 data)
         * = 19 × 523
         * = 9937
         *
         * Затем direct push 62 bytes:
         *
         * 1 + 62 = 63
         *
         * Итого:
         *
         * 9937 + 63 = 10000 bytes.
         *
         * Push opcodes не расходуют op-count.
         * Stack получает всего 20 элементов.
         */
        byte[] script =
                new byte[
                        ScriptLimits.MAX_SCRIPT_SIZE
                        ];

        int position = 0;

        for (int i = 0; i < 19; i++) {

            script[position++] =
                    (byte) Opcode.OP_PUSHDATA2;

            script[position++] =
                    0x08;

            script[position++] =
                    0x02;

            position +=
                    ScriptLimits.MAX_SCRIPT_ELEMENT_SIZE;
        }

        /*
         * Последние 63 bytes:
         *
         * PUSH62 + 62 data bytes.
         */
        script[position++] =
                62;

        position +=
                62;

        assertEquals(
                ScriptLimits.MAX_SCRIPT_SIZE,
                position
        );

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );

        assertEquals(
                20,
                machine.size()
        );
    }

    @Test
    void exactly201OperationsMustPass() {

        byte[] script =
                new byte[
                        ScriptLimits.MAX_OPS_PER_SCRIPT
                        ];

        Arrays.fill(
                script,
                (byte)
                        Opcode.OP_CODESEPARATOR
        );

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );
    }

    @Test
    void operation202MustFail() {

        byte[] script =
                new byte[
                        ScriptLimits.MAX_OPS_PER_SCRIPT
                                + 1
                        ];

        Arrays.fill(
                script,
                (byte)
                        Opcode.OP_CODESEPARATOR
        );

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );
    }

    @Test
    void scriptLargerThanTenThousandBytesMustFail() {

        byte[] script =
                new byte[
                        ScriptLimits.MAX_SCRIPT_SIZE
                                + 1
                        ];

        Arrays.fill(
                script,
                (byte)
                        Opcode.OP_CODESEPARATOR
        );

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );
    }

    @Test
    void pushOfExactly520BytesMustPass() {

        byte[] data =
                new byte[
                        ScriptLimits.MAX_SCRIPT_ELEMENT_SIZE
                        ];

        byte[] script =
                pushData2(
                        data
                );

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                data,
                machine.peek()
        );
    }

    @Test
    void pushOf521BytesMustFail() {

        byte[] data =
                new byte[
                        ScriptLimits.MAX_SCRIPT_ELEMENT_SIZE
                                + 1
                        ];

        byte[] script =
                pushData2(
                        data
                );

        ScriptMachine machine =
                new ScriptMachine();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );
    }

    @Test
    void exactlyOneThousandStackElementsMustPass() {

        ScriptMachine machine =
                new ScriptMachine();

        for (int i = 0;
             i < ScriptLimits.MAX_STACK_SIZE;
             i++) {

            machine.push(
                    new byte[0]
            );
        }

        assertEquals(
                ScriptLimits.MAX_STACK_SIZE,
                machine.size()
        );
    }

    @Test
    void oneThousandAndFirstCombinedStackElementMustFailValidation() {

        ScriptMachine machine =
                new ScriptMachine();

        for (int i = 0;
             i < ScriptLimits.MAX_STACK_SIZE + 1;
             i++) {

            machine.push(
                    new byte[0]
            );
        }

        assertEquals(
                ScriptLimits.MAX_STACK_SIZE + 1,
                machine.size()
        );

        assertThrows(
                ScriptExecutionException.class,
                machine::validateStackSize
        );
    }
    @Test
    void exactlyOneThousandCombinedStackElementsMustPassValidation() {

        ScriptMachine machine =
                new ScriptMachine();

        for (int i = 0;
             i < ScriptLimits.MAX_STACK_SIZE;
             i++) {

            machine.push(
                    new byte[0]
            );
        }

        assertDoesNotThrow(
                machine::validateStackSize
        );
    }

    private static byte[] pushData2(
            byte[] data
    ) {

        int length =
                data.length;

        byte[] script =
                new byte[
                        3 + length
                        ];

        script[0] =
                (byte)
                        Opcode.OP_PUSHDATA2;

        script[1] =
                (byte)
                        (
                                length
                                        & 0xff
                        );

        script[2] =
                (byte)
                        (
                                (length >>> 8)
                                        & 0xff
                        );

        System.arraycopy(
                data,
                0,
                script,
                3,
                length
        );

        return script;
    }
}
package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptUnaryNumericOpcodeTest {

    private static long topNumber(
            ScriptMachine machine
    ) {
        return ScriptNumber.decode(
                machine.peek(),
                5
        );
    }

    @Test
    void oneAddMustIncrementValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_1ADD
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                6,
                topNumber(machine)
        );
    }

    @Test
    void oneSubMustDecrementValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_1SUB
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                4,
                topNumber(machine)
        );
    }

    @Test
    void negateMustNegatePositiveValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NEGATE
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                -5,
                topNumber(machine)
        );
    }

    @Test
    void negateMustNegateNegativeValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NEGATE,
                (byte) Opcode.OP_NEGATE
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                5,
                topNumber(machine)
        );
    }

    @Test
    void absMustKeepPositiveValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_ABS
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                5,
                topNumber(machine)
        );
    }

    @Test
    void absMustConvertNegativeValueToPositive() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NEGATE,
                (byte) Opcode.OP_ABS
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                5,
                topNumber(machine)
        );
    }

    @Test
    void notMustReturnOneForZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_NOT
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                topNumber(machine)
        );
    }

    @Test
    void notMustReturnZeroForNonZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NOT
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                0,
                topNumber(machine)
        );
    }

    @Test
    void zeroNotEqualMustReturnZeroForZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_0NOTEQUAL
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                0,
                topNumber(machine)
        );
    }

    @Test
    void zeroNotEqualMustReturnOneForNonZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_0NOTEQUAL
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                topNumber(machine)
        );
    }

    @Test
    void notMustTreatNegativeZeroAsZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                0x01,
                (byte) 0x80,

                (byte) Opcode.OP_NOT
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                topNumber(machine)
        );
    }

    @Test
    void zeroNotEqualMustTreatNegativeZeroAsZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                0x01,
                (byte) 0x80,

                (byte) Opcode.OP_0NOTEQUAL
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                0,
                topNumber(machine)
        );
    }

    @Test
    void oneAddMayProduceFiveByteResult() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 2147483647
         *
         * ScriptNum encoding:
         *
         * ff ff ff 7f
         */
        byte[] script = {
                0x04,
                (byte) 0xff,
                (byte) 0xff,
                (byte) 0xff,
                0x7f,

                (byte) Opcode.OP_1ADD
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 2147483648 требует уже 5-byte ScriptNum.
         *
         * Это допустимый РЕЗУЛЬТАТ операции.
         */
        assertEquals(
                2_147_483_648L,
                topNumber(machine)
        );

        assertEquals(
                5,
                machine.peek().length
        );
    }

    @Test
    void fiveByteNumericOperandMustFail() {

        /*
         * Push минимального ScriptNum,
         * занимающего 5 bytes.
         *
         * Numeric opcode обязан reject operand > 4 bytes.
         */
        byte[] script = {
                0x05,
                0x00,
                0x00,
                0x00,
                (byte) 0x80,
                0x00,

                (byte) Opcode.OP_1ADD
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine()
                        )
        );
    }

    @Test
    void fiveByteResultCannotBeUsedByNextNumericOpcode() {

        /*
         * max 4-byte ScriptNum:
         *
         * 2147483647
         */
        byte[] script = {
                0x04,
                (byte) 0xff,
                (byte) 0xff,
                (byte) 0xff,
                0x7f,

                /*
                 * Result becomes 2147483648,
                 * encoded in 5 bytes.
                 */
                (byte) Opcode.OP_1ADD,

                /*
                 * Второй numeric opcode должен
                 * попытаться прочитать уже 5-byte operand
                 * с maxNumSize = 4 и завершиться ошибкой.
                 */
                (byte) Opcode.OP_1ADD
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine()
                        )
        );
    }

    @Test
    void unaryNumericOpcodeMustFailOnEmptyStack() {

        byte[] script = {
                (byte) Opcode.OP_NEGATE
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine()
                        )
        );
    }
}
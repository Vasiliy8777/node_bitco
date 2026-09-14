package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptBinaryNumericOpcodeTest {

    private static long topNumber(
            ScriptMachine machine
    ) {
        return ScriptNumber.decode(
                machine.peek(),
                5
        );
    }

    @Test
    void addMustAddTwoNumbers() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_ADD
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
    void subMustUseCorrectOperandOrder() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_SUB
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                3,
                topNumber(machine)
        );
    }

    @Test
    void boolAndMustReturnOneOnlyWhenBothNonZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_BOOLAND
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
    void boolAndMustReturnZeroWhenOneOperandIsZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_BOOLAND
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
    void boolOrMustReturnOneWhenOneOperandIsNonZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_BOOLOR
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
    void numEqualMustCompareNumerically() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NUMEQUAL
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
    void numEqualVerifyMustConsumeTrueResult() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NUMEQUALVERIFY,
                (byte) Opcode.OP_7
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                7,
                topNumber(machine)
        );
    }

    @Test
    void numEqualVerifyMustFailForDifferentValues() {

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_6,
                (byte) Opcode.OP_NUMEQUALVERIFY
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
    void numNotEqualMustReturnOneForDifferentValues() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_4,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_NUMNOTEQUAL
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
    void lessThanMustUseCorrectOperandOrder() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_LESSTHAN
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
    void greaterThanMustUseCorrectOperandOrder() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_GREATERTHAN
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
    void lessThanOrEqualMustAcceptEqualValues() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_LESSTHANOREQUAL
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
    void greaterThanOrEqualMustAcceptEqualValues() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_GREATERTHANOREQUAL
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
    void minMustReturnSmallerValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_7,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_MIN
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                3,
                topNumber(machine)
        );
    }

    @Test
    void maxMustReturnLargerValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_7,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_MAX
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                7,
                topNumber(machine)
        );
    }

    @Test
    void withinMustUseInclusiveLowerExclusiveUpperBound() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                /*
                 * x = 5
                 * min = 5
                 * max = 10
                 *
                 * 5 <= 5 && 5 < 10
                 */
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_10,
                (byte) Opcode.OP_WITHIN
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
    void withinMustRejectUpperBoundary() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                /*
                 * x = 10
                 * min = 5
                 * max = 10
                 *
                 * upper boundary exclusive.
                 */
                (byte) Opcode.OP_10,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_10,
                (byte) Opcode.OP_WITHIN
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
    void numericComparisonMustTreatNegativeZeroAsZero() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                /*
                 * negative zero
                 */
                0x01,
                (byte) 0x80,

                (byte) Opcode.OP_0,
                (byte) Opcode.OP_NUMEQUAL
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
    void addMayProduceFiveByteResult() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 2147483647 + 1
         */
        byte[] script = {
                0x04,
                (byte) 0xff,
                (byte) 0xff,
                (byte) 0xff,
                0x7f,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ADD
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

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
    void fiveByteOperandMustFailForBinaryNumericOpcode() {

        byte[] script = {
                /*
                 * 5-byte ScriptNum operand.
                 */
                0x05,
                0x00,
                0x00,
                0x00,
                (byte) 0x80,
                0x00,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ADD
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
    void binaryNumericOpcodeMustFailOnStackUnderflow() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_ADD
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
    void withinMustFailOnStackUnderflow() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_WITHIN
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
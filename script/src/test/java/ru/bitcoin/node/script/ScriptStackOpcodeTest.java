package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptStackOpcodeTest {

    private static long numberAt(
            ScriptMachine machine,
            int depth
    ) {
        return ScriptNumber.decode(
                machine.peekFromTop(depth),
                4
        );
    }

    @Test
    void twoDropMustRemoveTopTwo() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_2DROP
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(1, machine.size());
        assertEquals(1, numberAt(machine, 0));
    }

    @Test
    void twoDupMustDuplicateTopPair() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_2DUP
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(4, machine.size());

        assertEquals(2, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
        assertEquals(2, numberAt(machine, 2));
        assertEquals(1, numberAt(machine, 3));
    }

    @Test
    void threeDupMustDuplicateTopThree() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_3DUP
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(6, machine.size());

        assertEquals(3, numberAt(machine, 0));
        assertEquals(2, numberAt(machine, 1));
        assertEquals(1, numberAt(machine, 2));
        assertEquals(3, numberAt(machine, 3));
        assertEquals(2, numberAt(machine, 4));
        assertEquals(1, numberAt(machine, 5));
    }

    @Test
    void pickMustCopySelectedElement() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_4,

                (byte) Opcode.OP_2,
                (byte) Opcode.OP_PICK
        };

        ScriptInterpreter.execute(script, machine);

        /*
         * 1 2 3 4 2 PICK
         *
         * => 1 2 3 4 2
         */
        assertEquals(5, machine.size());

        assertEquals(
                2,
                numberAt(machine, 0)
        );

        assertEquals(
                4,
                numberAt(machine, 1)
        );
    }

    @Test
    void rollMustMoveSelectedElementToTop() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_4,

                (byte) Opcode.OP_2,
                (byte) Opcode.OP_ROLL
        };

        ScriptInterpreter.execute(script, machine);

        /*
         * 1 2 3 4 2 ROLL
         *
         * => 1 3 4 2
         */
        assertEquals(4, machine.size());

        assertEquals(2, numberAt(machine, 0));
        assertEquals(4, numberAt(machine, 1));
        assertEquals(3, numberAt(machine, 2));
        assertEquals(1, numberAt(machine, 3));
    }

    @Test
    void rotMustRotateTopThree() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_ROT
        };

        ScriptInterpreter.execute(script, machine);

        /*
         * 1 2 3 -> 2 3 1
         */
        assertEquals(1, numberAt(machine, 0));
        assertEquals(3, numberAt(machine, 1));
        assertEquals(2, numberAt(machine, 2));
    }

    @Test
    void swapMustSwapTopTwo() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_SWAP
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(1, numberAt(machine, 0));
        assertEquals(2, numberAt(machine, 1));
    }

    @Test
    void tuckMustInsertTopBelowSecond() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_TUCK
        };

        ScriptInterpreter.execute(script, machine);

        /*
         * 1 2 -> 2 1 2
         */
        assertEquals(2, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
        assertEquals(2, numberAt(machine, 2));
    }

    @Test
    void depthMustPushCurrentDepth() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_DEPTH
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(4, machine.size());

        assertEquals(
                3,
                numberAt(machine, 0)
        );
    }

    @Test
    void sizeMustPushByteLengthWithoutRemovingValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                0x03,
                0x11,
                0x22,
                0x33,

                (byte) Opcode.OP_SIZE
        };

        ScriptInterpreter.execute(script, machine);

        assertEquals(2, machine.size());

        assertEquals(
                3,
                numberAt(machine, 0)
        );

        assertArrayEquals(
                new byte[]{
                        0x11,
                        0x22,
                        0x33
                },
                machine.peekFromTop(1)
        );
    }

    @Test
    void negativePickDepthMustFail() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_1NEGATE,
                (byte) Opcode.OP_PICK
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
    void stackOpcodeUnderflowMustFail() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2SWAP
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
    void twoOverMustCopyFourthAndThirdElements() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_4,

                (byte) Opcode.OP_2OVER
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2 3 4
         *
         * OP_2OVER
         *
         * => 1 2 3 4 1 2
         */
        assertEquals(
                6,
                machine.size()
        );

        assertEquals(2, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
        assertEquals(4, numberAt(machine, 2));
        assertEquals(3, numberAt(machine, 3));
        assertEquals(2, numberAt(machine, 4));
        assertEquals(1, numberAt(machine, 5));
    }

    @Test
    void twoRotMustRotateTopSixElements() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_4,
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_6,

                (byte) Opcode.OP_2ROT
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2 3 4 5 6
         *
         * OP_2ROT
         *
         * => 3 4 5 6 1 2
         */
        assertEquals(
                6,
                machine.size()
        );

        assertEquals(2, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
        assertEquals(6, numberAt(machine, 2));
        assertEquals(5, numberAt(machine, 3));
        assertEquals(4, numberAt(machine, 4));
        assertEquals(3, numberAt(machine, 5));
    }

    @Test
    void twoSwapMustSwapTopTwoPairs() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,
                (byte) Opcode.OP_4,

                (byte) Opcode.OP_2SWAP
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2 3 4
         *
         * OP_2SWAP
         *
         * => 3 4 1 2
         */
        assertEquals(
                4,
                machine.size()
        );

        assertEquals(2, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
        assertEquals(4, numberAt(machine, 2));
        assertEquals(3, numberAt(machine, 3));
    }

    @Test
    void ifDupMustDuplicateTrueTopValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_5,
                (byte) Opcode.OP_IFDUP
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 5 -> 5 5
         */
        assertEquals(
                2,
                machine.size()
        );

        assertEquals(5, numberAt(machine, 0));
        assertEquals(5, numberAt(machine, 1));
    }

    @Test
    void ifDupMustNotDuplicateFalseTopValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IFDUP
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 0 остаётся единственным элементом.
         */
        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                0,
                numberAt(machine, 0)
        );
    }

    @Test
    void nipMustRemoveSecondElementFromTop() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,

                (byte) Opcode.OP_NIP
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2 3
         *
         * OP_NIP
         *
         * => 1 3
         */
        assertEquals(
                2,
                machine.size()
        );

        assertEquals(3, numberAt(machine, 0));
        assertEquals(1, numberAt(machine, 1));
    }

    @Test
    void overMustCopySecondElementFromTop() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_OVER
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2
         *
         * OP_OVER
         *
         * => 1 2 1
         */
        assertEquals(
                3,
                machine.size()
        );

        assertEquals(1, numberAt(machine, 0));
        assertEquals(2, numberAt(machine, 1));
        assertEquals(1, numberAt(machine, 2));
    }

    @Test
    void pickZeroMustDuplicateTopElement() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,

                (byte) Opcode.OP_0,
                (byte) Opcode.OP_PICK
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * 1 2 3 0 PICK
         *
         * => 1 2 3 3
         */
        assertEquals(
                4,
                machine.size()
        );

        assertEquals(3, numberAt(machine, 0));
        assertEquals(3, numberAt(machine, 1));
    }

    @Test
    void rollZeroMustLeaveSelectedTopElementAtTop() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_3,

                (byte) Opcode.OP_0,
                (byte) Opcode.OP_ROLL
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * n = 0:
         *
         * снимаем 3 и сразу помещаем обратно.
         *
         * => 1 2 3
         */
        assertEquals(
                3,
                machine.size()
        );

        assertEquals(3, numberAt(machine, 0));
        assertEquals(2, numberAt(machine, 1));
        assertEquals(1, numberAt(machine, 2));
    }

    @Test
    void pickDepthEqualToStackSizeMustFail() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,

                /*
                 * После удаления depth:
                 *
                 * stack = [1, 2]
                 *
                 * valid depths: 0 и 1.
                 *
                 * depth = 2 уже вне stack.
                 */
                (byte) Opcode.OP_2,
                (byte) Opcode.OP_PICK
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
    void rollDepthEqualToStackSizeMustFail() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_2,
                (byte) Opcode.OP_ROLL
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
    void sizeMustUseSerializedByteLengthNotNumericValue() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] script = {

                /*
                 * Push:
                 *
                 * 01 00
                 *
                 * Численно ScriptNumber = 1,
                 * но размер stack element = 2 bytes.
                 */
                0x02,
                0x01,
                0x00,

                (byte) Opcode.OP_SIZE
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                2,
                machine.size()
        );

        /*
         * OP_SIZE должен положить 2.
         */
        assertEquals(
                2,
                numberAt(machine, 0)
        );

        /*
         * Исходный элемент при этом остаётся.
         */
        assertArrayEquals(
                new byte[]{
                        0x01,
                        0x00
                },
                machine.peekFromTop(1)
        );
    }
    @Test
    void ifDupMustTreatNegativeZeroAsFalse() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 0x80 = Bitcoin Script negative zero.
         */
        byte[] script = {
                0x01,
                (byte) 0x80,

                (byte) Opcode.OP_IFDUP
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        /*
         * Negative zero является false,
         * поэтому дублирования быть не должно.
         */
        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{
                        (byte) 0x80
                },
                machine.peek()
        );
    }
}
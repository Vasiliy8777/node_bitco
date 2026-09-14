package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ScriptConditionalTest {

    @Test
    void ifTrueMustExecuteTrueBranch() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_IF,

                0x01,
                0x2a,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{0x2a},
                machine.peek()
        );
    }

    @Test
    void ifFalseMustSkipTrueBranch() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                0x01,
                0x2a,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertTrue(
                machine.isEmpty()
        );
    }

    @Test
    void elseMustExecuteOnlySelectedBranch() {

        byte[] script = {
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_IF,

                0x01,
                0x11,

                (byte) Opcode.OP_ELSE,

                0x01,
                0x22,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{0x22},
                machine.peek()
        );
    }

    @Test
    void notIfMustInvertCondition() {

        byte[] script = {
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_NOTIF,

                0x01,
                0x2a,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertArrayEquals(
                new byte[]{0x2a},
                machine.peek()
        );
    }

    @Test
    void nestedInactiveBranchMustNotConsumeStack() {

        byte[] script = {
                /*
                 * outer condition = false
                 */
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_IF,

                /*
                 * Если interpreter ошибочно выполняет
                 * inner IF, здесь возникнет underflow.
                 */
                (byte) Opcode.OP_IF,
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_ENDIF,

                (byte) Opcode.OP_ENDIF,

                /*
                 * После conditional кладём результат.
                 */
                (byte) Opcode.OP_1
        };

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

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }

    @Test
    void unmatchedElseMustFail() {

        byte[] script = {
                (byte) Opcode.OP_ELSE
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
    void unmatchedEndIfMustFail() {

        byte[] script = {
                (byte) Opcode.OP_ENDIF
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
    void missingEndIfMustFail() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_IF,
                (byte) Opcode.OP_1
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
    void inactiveBranchOpcodesMustStillCountTowardOperationLimit() {

        /*
         * OP_0 OP_IF
         *
         * false branch:
         * 200 × OP_NOP
         *
         * OP_ENDIF
         *
         * op-count:
         *
         * OP_IF    = 1
         * 200 NOP  = 200
         * OP_ENDIF = 1
         *
         * total = 202 => FAIL
         *
         * Ветка не выполняется, но op-count всё равно
         * должен применяться.
         */
        byte[] script =
                new byte[
                        1 + 1 + 200 + 1
                        ];

        int position = 0;

        script[position++] =
                (byte) Opcode.OP_0;

        script[position++] =
                (byte) Opcode.OP_IF;

        Arrays.fill(
                script,
                position,
                position + 200,
                (byte) Opcode.OP_NOP
        );

        position += 200;

        script[position] =
                (byte) Opcode.OP_ENDIF;

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
    void codeSeparatorInsideInactiveBranchMustNotBecomeActive() {

        /*
         * Пока мы не пытаемся подписывать транзакцию,
         * здесь проверяем только отсутствие выполнения
         * CODESEPARATOR внутри false branch.
         *
         * Этот regression станет особенно важным
         * при следующих CHECKSIG-тестах.
         */
        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_CODESEPARATOR,

                (byte) Opcode.OP_ENDIF,

                (byte) Opcode.OP_1
        };

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine
                        )
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.peek()
                )
        );
    }
}
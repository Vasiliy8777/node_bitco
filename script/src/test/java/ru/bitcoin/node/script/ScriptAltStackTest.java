package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptAltStackTest {

    @Test
    void toAltStackAndFromAltStackMustPreserveValue() {

        byte[] script = {
                0x01,
                0x2a,
                (byte) Opcode.OP_TOALTSTACK,
                (byte) Opcode.OP_FROMALTSTACK
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

        assertEquals(
                0,
                machine.altSize()
        );

        assertArrayEquals(
                new byte[]{0x2a},
                machine.peek()
        );
    }

    @Test
    void toAltStackMustRemoveValueFromMainStack() {

        byte[] script = {
                0x01,
                0x2a,
                (byte) Opcode.OP_TOALTSTACK
        };

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertEquals(
                0,
                machine.size()
        );

        assertEquals(
                1,
                machine.altSize()
        );

        assertArrayEquals(
                new byte[]{0x2a},
                machine.peekAlt()
        );
    }

    @Test
    void fromAltStackWithEmptyAltStackMustFail() {

        byte[] script = {
                (byte) Opcode.OP_FROMALTSTACK
        };

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
    void combinedStackLimitMustCountAltStack() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 600 main-stack elements.
         */
        for (int i = 0; i < 600; i++) {
            machine.push(
                    new byte[0]
            );
        }

        /*
         * 401 altstack elements.
         */
        for (int i = 0; i < 401; i++) {
            machine.pushAlt(
                    new byte[0]
            );
        }

        assertEquals(
                600,
                machine.size()
        );

        assertEquals(
                401,
                machine.altSize()
        );

        assertEquals(
                1001,
                machine.totalStackSize()
        );

        assertThrows(
                ScriptExecutionException.class,
                machine::validateStackSize
        );
    }

    @Test
    void combinedStackExactlyOneThousandMustPass() {

        ScriptMachine machine =
                new ScriptMachine();

        for (int i = 0; i < 600; i++) {
            machine.push(
                    new byte[0]
            );
        }

        for (int i = 0; i < 400; i++) {
            machine.pushAlt(
                    new byte[0]
            );
        }

        assertEquals(
                1000,
                machine.totalStackSize()
        );

        assertDoesNotThrow(
                machine::validateStackSize
        );
    }

    @Test
    void copyMustCopyBothStacksIndependently() {

        ScriptMachine original =
                new ScriptMachine();

        original.push(
                new byte[]{0x01}
        );

        original.pushAlt(
                new byte[]{0x02}
        );

        ScriptMachine copy =
                original.copy();

        assertEquals(
                1,
                copy.size()
        );

        assertEquals(
                1,
                copy.altSize()
        );

        assertArrayEquals(
                new byte[]{0x01},
                copy.peek()
        );

        assertArrayEquals(
                new byte[]{0x02},
                copy.peekAlt()
        );

        /*
         * Изменение copy не должно менять original.
         */
        copy.pop();
        copy.popAlt();

        assertEquals(
                1,
                original.size()
        );

        assertEquals(
                1,
                original.altSize()
        );
    }
    @Test
    void altStackMustNotSurviveBetweenEvalScriptCalls() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Первый EvalScript:
         *
         * PUSH 42
         * TOALTSTACK
         *
         * После его выполнения main stack пуст,
         * а altstack содержит 42.
         */
        byte[] firstScript = {
                0x01,
                0x2a,
                (byte) Opcode.OP_TOALTSTACK
        };

        ScriptInterpreter.execute(
                firstScript,
                machine
        );

        assertEquals(
                0,
                machine.size()
        );

        assertEquals(
                1,
                machine.altSize()
        );

        /*
         * Второй execute() = новый EvalScript.
         *
         * altstack обязан быть очищен ДО исполнения,
         * поэтому OP_FROMALTSTACK должен завершиться
         * underflow.
         */
        byte[] secondScript = {
                (byte) Opcode.OP_FROMALTSTACK
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                secondScript,
                                machine
                        )
        );

        assertEquals(
                0,
                machine.altSize()
        );
    }
    @Test
    void mainStackMustSurviveBetweenEvalScriptCalls() {

        ScriptMachine machine =
                new ScriptMachine();

        byte[] firstScript = {
                0x01,
                0x2a
        };

        ScriptInterpreter.execute(
                firstScript,
                machine
        );

        assertEquals(
                1,
                machine.size()
        );

        /*
         * Новый EvalScript использует тот же
         * основной stack.
         */
        byte[] secondScript = {
                (byte) Opcode.OP_DUP
        };

        ScriptInterpreter.execute(
                secondScript,
                machine
        );

        assertEquals(
                2,
                machine.size()
        );

        assertArrayEquals(
                new byte[]{0x2a},
                machine.peek()
        );
    }
}
package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptDisabledOpcodeTest {

    @Test
    void nopMustDoNothing() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_NOP
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
    void opReturnInExecutedBranchMustFail() {

        byte[] script = {
                (byte) Opcode.OP_RETURN
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
    void opReturnInsideInactiveBranchMustNotFail() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_RETURN,

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
    void disabledOpcodeMustFailWhenExecuted() {

        byte[] script = {
                (byte) Opcode.OP_CAT
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
    void disabledOpcodeMustFailInsideInactiveBranch() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_CAT,

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
    void verIfMustFailInsideInactiveBranch() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_VERIF,

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
    void verNotIfMustFailInsideInactiveBranch() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_VERNOTIF,

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
    void reservedOpcodeInsideInactiveBranchMustBeSkipped() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_RESERVED,

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

    @Test
    void reservedOpcodeWhenExecutedMustFail() {

        byte[] script = {
                (byte) Opcode.OP_RESERVED
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
    void upgradableNopMustPassAtConsensusLevel() {

        byte[] script = {
                (byte) Opcode.OP_1,
                (byte) Opcode.OP_NOP4
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
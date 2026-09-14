package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptMinimalDataTest {

    @Test
    void nonMinimalPushMustPassWithoutMinimalDataFlag() {

        /*
         * PUSHDATA1 01 11
         *
         * Push одного обычного байта через PUSHDATA1.
         * Функционально корректно, но не minimal.
         */
        byte[] script = {
                (byte) Opcode.OP_PUSHDATA1,
                0x01,
                0x11
        };

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context(
                                        script,
                                        ScriptVerifyFlags.NONE
                                )
                        )
        );

        assertArrayEquals(
                new byte[]{
                        0x11
                },
                machine.peek()
        );
    }

    @Test
    void nonMinimalPushMustFailWithMinimalDataFlag() {

        byte[] script = {
                (byte) Opcode.OP_PUSHDATA1,
                0x01,
                0x11
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine(),
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );
    }

    @Test
    void directPushOfOrdinaryByteMustPassMinimalData() {

        byte[] script = {
                0x01,
                0x11
        };

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );

        assertArrayEquals(
                new byte[]{
                        0x11
                },
                machine.peek()
        );
    }

    @Test
    void directPushOfNumberOneMustFailMinimalData() {

        /*
         * 01 01
         *
         * Должен использоваться OP_1.
         */
        byte[] script = {
                0x01,
                0x01
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine(),
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );
    }

    @Test
    void opOneMustPassMinimalData() {

        byte[] script = {
                (byte) Opcode.OP_1
        };

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );

        assertEquals(
                1,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void nonMinimalNumericOperandMustFailWhenActuallyUsedAsNumber() {

        /*
         * 02 01 00
         *
         * Stack element = [01 00].
         * Это число 1, но ScriptNum encoding non-minimal.
         *
         * Сам push здесь минимален по длине:
         * два байта через direct push 02.
         *
         * Ошибка должна появиться именно на OP_1ADD.
         */
        byte[] script = {
                0x02,
                0x01,
                0x00,

                (byte) Opcode.OP_1ADD
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine(),
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );
    }

    @Test
    void sameNonMinimalNumericOperandMustPassWithoutFlag() {

        byte[] script = {
                0x02,
                0x01,
                0x00,

                (byte) Opcode.OP_1ADD
        };

        ScriptMachine machine =
                new ScriptMachine();

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                machine,
                                context(
                                        script,
                                        ScriptVerifyFlags.NONE
                                )
                        )
        );

        assertEquals(
                2,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void nonMinimalPushInsideInactiveBranchMustNotFail() {

        /*
         * OP_0 OP_IF
         *      PUSHDATA1 01 11
         * OP_ENDIF
         *
         * Push находится в false branch.
         *
         * Core проверяет MINIMALDATA только для
         * реально выполняемого push.
         */
        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_PUSHDATA1,
                0x01,
                0x11,

                (byte) Opcode.OP_ENDIF
        };

        assertDoesNotThrow(
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine(),
                                context(
                                        script,
                                        ScriptVerifyFlags.MINIMALDATA
                                )
                        )
        );
    }

    private static ScriptExecutionContext context(
            byte[] script,
            int flags
    ) {

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                Hash256.fromDisplayHex(
                                                        "00".repeat(32)
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        new UInt32(0)
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        return new ScriptExecutionContext(
                transaction,
                0,
                script,
                flags
        );
    }
}
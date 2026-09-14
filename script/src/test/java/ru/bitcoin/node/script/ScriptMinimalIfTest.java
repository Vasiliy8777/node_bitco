package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptMinimalIfTest {

    private static final int MINIMALIF =
            ScriptVerifyFlags.MINIMALIF;

    /*
     * WITNESS_V0 + MINIMALIF:
     *
     * false должен быть только:
     *
     * []
     *
     * true должен быть только:
     *
     * [01]
     */

    @Test
    void witnessV0MinimalIfMustAcceptEmptyFalse() {

        byte[] script = {
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_IF,

                /*
                 * Эта ветка не выполняется.
                 */
                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                );

        /*
         * OP_0 был снят OP_IF.
         * Ветка false ничего не положила.
         */
        assertEquals(
                0,
                machine.size()
        );
    }

    @Test
    void witnessV0MinimalIfMustAcceptOneAsTrue() {

        byte[] script = {
                (byte) Opcode.OP_1,

                (byte) Opcode.OP_IF,

                /*
                 * Результат выполняемой ветки.
                 */
                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                );

        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                1L,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustRejectTwo() {

        /*
         * OP_2 означает stack value [02].
         *
         * CastToBool([02]) == true,
         * но для MINIMALIF это запрещённое
         * представление true.
         */
        byte[] script = {
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustRejectNegativeOne() {

        /*
         * OP_1NEGATE кладёт [81].
         *
         * Это true по CastToBool,
         * но не [01].
         */
        byte[] script = {
                (byte) Opcode.OP_1NEGATE,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustRejectNegativeZero() {

        /*
         * 01 80
         *
         * Push одного байта:
         *
         * [80]
         *
         * Script castToBool считает его false
         * (negative zero).
         *
         * Но MINIMALIF разрешает false
         * только как empty vector [].
         *
         * MINIMALDATA намеренно НЕ включаем,
         * чтобы ошибка происходила именно
         * из-за MINIMALIF.
         */
        byte[] script = {
                0x01,
                (byte) 0x80,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0WithoutMinimalIfFlagMustAcceptNonMinimalTrue() {

        /*
         * [02] является true.
         *
         * Без MINIMALIF это допустимое
         * Bitcoin Script boolean value.
         */
        byte[] script = {
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                execute(
                        script,
                        ScriptVerifyFlags.NONE,
                        SignatureVersion.WITNESS_V0
                );

        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                1L,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void legacyMustIgnoreMinimalIfFlag() {

        /*
         * Даже если MINIMALIF присутствует
         * среди flags, legacy Script execution
         * не должен применять witness-v0
         * MINIMALIF semantics.
         *
         * [02] остаётся обычным true.
         */
        byte[] script = {
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.LEGACY
                );

        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                1L,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void minimalIfMustOnlyCheckExecutedIf() {

        /*
         * Outer condition = false.
         *
         * Поэтому весь внутренний блок,
         * включая OP_2 и вложенный OP_IF,
         * находится в inactive branch.
         *
         * Вложенный OP_IF не читает stack
         * и MINIMALIF не должен выполняться.
         */
        byte[] script = {
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_IF,

                /*
                 * Не выполняется.
                 */
                (byte) Opcode.OP_2,

                /*
                 * Этот IF структурно разбирается,
                 * но condition со stack не читает,
                 * потому что parent branch inactive.
                 */
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF,

                (byte) Opcode.OP_ENDIF
        };

        assertDoesNotThrow(
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustAlsoApplyToNotIf() {

        /*
         * MINIMALIF одинаково относится
         * к OP_IF и OP_NOTIF.
         *
         * [02] — non-minimal boolean.
         */
        byte[] script = {
                (byte) Opcode.OP_2,

                (byte) Opcode.OP_NOTIF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0MinimalNotIfMustAcceptEmptyFalse() {

        /*
         * []
         *
         * OP_NOTIF:
         *
         * false -> NOT -> true
         *
         * Поэтому OP_1 внутри ветки выполняется.
         */
        byte[] script = {
                (byte) Opcode.OP_0,

                (byte) Opcode.OP_NOTIF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        ScriptMachine machine =
                execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                );

        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                1L,
                ScriptNumber.decode(
                        machine.peek(),
                        4
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustRejectZeroByte() {

        /*
         * [00] является false по CastToBool.
         *
         * Но MINIMALIF требует для false
         * именно empty vector [].
         *
         * Это отличается от OP_0:
         *
         * OP_0 -> []
         *
         * direct push 01 00 -> [00]
         */
        byte[] script = {
                0x01,
                0x00,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    @Test
    void witnessV0MinimalIfMustRejectMultiByteOne() {

        /*
         * [01 00] численно соответствует 1,
         * но MINIMALIF вообще не выполняет
         * ScriptNum normalization.
         *
         * Единственный разрешённый true:
         *
         * [01]
         */
        byte[] script = {
                0x02,
                0x01,
                0x00,

                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_1,

                (byte) Opcode.OP_ENDIF
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        MINIMALIF,
                        SignatureVersion.WITNESS_V0
                )
        );
    }

    private static ScriptMachine execute(
            byte[] script,
            int flags,
            SignatureVersion signatureVersion
    ) {

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine,
                new ScriptExecutionContext(
                        transaction(),
                        0,
                        script,
                        flags,
                        0L,
                        signatureVersion
                )
        );

        return machine;
    }

    private static Transaction transaction() {

        byte[] previousHash =
                new byte[32];

        /*
         * Не используем полностью zero hash,
         * чтобы fixture точно не выглядел
         * как coinbase prevout.
         */
        previousHash[0] =
                0x44;

        return new Transaction(
                2,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        new Hash256(
                                                previousHash
                                        ),
                                        new UInt32(0)
                                ),
                                new byte[0],
                                new UInt32(
                                        0xffff_fffeL
                                )
                        )
                ),
                List.of(
                        new TxOut(
                                1_000L,
                                new byte[]{
                                        (byte) Opcode.OP_1
                                }
                        )
                ),
                new UInt32(0)
        );
    }
}
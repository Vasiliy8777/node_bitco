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

class CheckSequenceVerifyTest {

    private static final int CSV =
            ScriptVerifyFlags.CHECKSEQUENCEVERIFY;

    private static final long DISABLE_FLAG =
            1L << 31;

    private static final long TYPE_FLAG =
            1L << 22;

    @Test
    void csvMustSucceedWhenSequencesAreEqual() {

        ScriptMachine machine =
                execute(
                        transaction(
                                2,
                                10L
                        ),
                        10L,
                        CSV
                );

        assertEquals(
                1,
                machine.size()
        );

        /*
         * CSV operand не удаляет.
         */
        assertEquals(
                10L,
                ScriptNumber.decode(
                        machine.peek(),
                        5
                )
        );
    }

    @Test
    void csvMustSucceedWhenInputSequenceIsGreater() {

        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                2,
                                20L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustFailWhenInputSequenceIsTooSmall() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                2,
                                9L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustRejectNegativeOperand() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                2,
                                10L
                        ),
                        -1L,
                        CSV
                )
        );
    }

    @Test
    void csvMustRequireTransactionVersionTwoOrGreater() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                1,
                                10L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustRejectInputDisableFlag() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                2,
                                DISABLE_FLAG | 10L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvOperandDisableFlagMustTurnOperationIntoNop() {

        /*
         * transaction version и input sequence здесь
         * намеренно не удовлетворяют обычному CSV.
         *
         * Но disable flag в SCRIPT operand означает,
         * что opcode должен вести себя как NOP.
         */
        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                1,
                                0xffff_ffffL
                        ),
                        DISABLE_FLAG,
                        CSV
                )
        );
    }

    @Test
    void csvMustRejectHeightOperandAgainstTimeSequence() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                2,
                                TYPE_FLAG | 10L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustRejectTimeOperandAgainstHeightSequence() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                2,
                                10L
                        ),
                        TYPE_FLAG | 10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustAcceptTimeBasedRelativeLock() {

        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                2,
                                TYPE_FLAG | 20L
                        ),
                        TYPE_FLAG | 10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustIgnoreIrrelevantSequenceBits() {

        /*
         * Bit 20 не участвует в BIP68 comparison.
         */
        long irrelevantBit =
                1L << 20;

        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                2,
                                irrelevantBit | 10L
                        ),
                        10L,
                        CSV
                )
        );
    }

    @Test
    void csvMustActAsNopWhenFlagIsDisabled() {

        /*
         * Без BIP112 flag opcode 0xb2 = OP_NOP3.
         */
        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                1,
                                0xffff_ffffL
                        ),
                        100L,
                        ScriptVerifyFlags.NONE
                )
        );
    }

    private static ScriptMachine execute(
            Transaction transaction,
            long requiredSequence,
            int flags
    ) {

        byte[] encoded =
                ScriptNumber.encode(
                        requiredSequence
                );

        byte[] script =
                new byte[
                        1
                                + encoded.length
                                + 1
                        ];

        script[0] =
                (byte) encoded.length;

        System.arraycopy(
                encoded,
                0,
                script,
                1,
                encoded.length
        );

        script[script.length - 1] =
                (byte) Opcode.OP_CHECKSEQUENCEVERIFY;

        ScriptMachine machine =
                new ScriptMachine();

        ScriptInterpreter.execute(
                script,
                machine,
                new ScriptExecutionContext(
                        transaction,
                        0,
                        script,
                        flags
                )
        );

        return machine;
    }

    private static Transaction transaction(
            int version,
            long sequence
    ) {

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x66;

        return new Transaction(
                version,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        new Hash256(
                                                previousHash
                                        ),
                                        new UInt32(0)
                                ),
                                new byte[0],
                                new UInt32(sequence)
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
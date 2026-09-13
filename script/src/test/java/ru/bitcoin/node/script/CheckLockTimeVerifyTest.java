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

class CheckLockTimeVerifyTest {

    private static final int CLTV =
            ScriptVerifyFlags.CHECKLOCKTIMEVERIFY;

    @Test
    void cltvMustSucceedWhenTransactionLockTimeEqualsRequirement() {

        Transaction transaction =
                transaction(
                        500L,
                        0xffff_fffeL
                );

        ScriptMachine machine =
                execute(
                        transaction,
                        ScriptNumber.encode(500L),
                        CLTV
                );

        /*
         * CLTV не удаляет operand.
         */
        assertEquals(
                1,
                machine.size()
        );

        assertEquals(
                500L,
                ScriptNumber.decode(
                        machine.peek(),
                        5
                )
        );
    }

    @Test
    void cltvMustSucceedWhenTransactionLockTimeIsGreater() {

        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                600L,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(500L),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustFailWhenTransactionLockTimeIsTooSmall() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                499L,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(500L),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustRejectNegativeRequirement() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                500L,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(-1L),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustRejectHeightAgainstTimestamp() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                500_000_000L,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(
                                499_999_999L
                        ),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustRejectTimestampAgainstHeight() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                499_999_999L,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(
                                500_000_000L
                        ),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustRejectFinalSequence() {

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        transaction(
                                500L,
                                0xffff_ffffL
                        ),
                        ScriptNumber.encode(500L),
                        CLTV
                )
        );
    }

    @Test
    void cltvMustActAsNopWhenFlagIsDisabled() {

        Transaction transaction =
                transaction(
                        0L,
                        0xffff_ffffL
                );

        ScriptMachine machine =
                execute(
                        transaction,
                        ScriptNumber.encode(
                                999_999L
                        ),
                        ScriptVerifyFlags.NONE
                );

        /*
         * Если BIP65 не активен, 0xb1 = NOP2.
         */
        assertEquals(
                1,
                machine.size()
        );
    }

    @Test
    void cltvMustAllowUnsigned32BitMaximum() {

        assertDoesNotThrow(
                () -> execute(
                        transaction(
                                0xffff_ffffL,
                                0xffff_fffeL
                        ),
                        ScriptNumber.encode(
                                0xffff_ffffL
                        ),
                        CLTV
                )
        );
    }

    private static ScriptMachine execute(
            Transaction transaction,
            byte[] lockTime,
            int flags
    ) {

        byte[] script =
                concatenate(
                        push(lockTime),
                        new byte[]{
                                (byte) Opcode.OP_CHECKLOCKTIMEVERIFY
                        }
                );

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
            long lockTime,
            long sequence
    ) {

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x55;

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
                new UInt32(lockTime)
        );
    }

    private static byte[] push(
            byte[] data
    ) {

        if (data.length > Opcode.OP_DATA_MAX) {
            throw new IllegalArgumentException();
        }

        byte[] result =
                new byte[data.length + 1];

        result[0] =
                (byte) data.length;

        System.arraycopy(
                data,
                0,
                result,
                1,
                data.length
        );

        return result;
    }

    private static byte[] concatenate(
            byte[] first,
            byte[] second
    ) {

        byte[] result =
                new byte[
                        first.length
                                + second.length
                        ];

        System.arraycopy(
                first,
                0,
                result,
                0,
                first.length
        );

        System.arraycopy(
                second,
                0,
                result,
                first.length,
                second.length
        );

        return result;
    }
}
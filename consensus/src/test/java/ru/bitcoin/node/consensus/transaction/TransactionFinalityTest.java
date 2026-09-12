package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionFinalityTest {

    @Test
    void shouldAcceptZeroLockTime() {

        Transaction transaction =
                transaction(
                        0,
                        0
                );

        assertTrue(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldAcceptHeightLockTimeBelowBlockHeight() {

        Transaction transaction =
                transaction(
                        99,
                        0
                );

        assertTrue(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldRejectHeightLockTimeEqualToBlockHeight() {

        Transaction transaction =
                transaction(
                        100,
                        0
                );

        assertFalse(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldRejectHeightLockTimeAboveBlockHeight() {

        Transaction transaction =
                transaction(
                        101,
                        0
                );

        assertFalse(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldAcceptTimeLockBelowBlockTime() {

        Transaction transaction =
                transaction(
                        1_699_999_999L,
                        0
                );

        assertTrue(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldRejectTimeLockEqualToBlockTime() {

        Transaction transaction =
                transaction(
                        1_700_000_000L,
                        0
                );

        assertFalse(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldIgnoreLockTimeWhenAllInputsAreFinal() {

        Transaction transaction =
                transaction(
                        200,
                        0xFFFF_FFFFL
                );

        assertTrue(
                TransactionFinality.isFinal(
                        transaction,
                        100,
                        1_700_000_000L
                )
        );
    }

    @Test
    void shouldThrowForNonFinalTransaction() {

        Transaction transaction =
                transaction(
                        101,
                        0
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        TransactionFinality.validate(
                                transaction,
                                100,
                                1_700_000_000L
                        )
        );
    }

    private static Transaction transaction(
            long lockTime,
            long sequence
    ) {
        return new Transaction(
                2,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        Hash256.fromDisplayHex(
                                                "11".repeat(32)
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
                                new byte[]{0x51}
                        )
                ),
                new UInt32(lockTime)
        );
    }
}
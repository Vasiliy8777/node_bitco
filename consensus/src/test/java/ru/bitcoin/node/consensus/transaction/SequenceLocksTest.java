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

class SequenceLocksTest {

    @Test
    void shouldIgnoreSequenceLocksForTransactionVersionBelowTwo() {

        Transaction transaction =
                transaction(
                        1,
                        new UInt32(5)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertEquals(
                SequenceLock.NONE,
                lock
        );
    }

    @Test
    void shouldIgnoreInputWhenDisableFlagIsSet() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_DISABLE_FLAG
                        | 5L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertEquals(
                SequenceLock.NONE,
                lock
        );
    }

    @Test
    void shouldCalculateHeightBasedSequenceLock() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(5)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertEquals(
                104L,
                lock.minimumHeight()
        );

        assertEquals(
                -1L,
                lock.minimumTime()
        );
    }

    @Test
    void shouldRejectHeightLockAtBoundary() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(5)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertFalse(
                SequenceLocks.evaluate(
                        lock,
                        104L,
                        2_000_000L
                )
        );
    }

    @Test
    void shouldAcceptHeightLockAfterBoundary() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(5)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertTrue(
                SequenceLocks.evaluate(
                        lock,
                        105L,
                        2_000_000L
                )
        );
    }

    @Test
    void shouldCalculateTimeBasedSequenceLock() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertEquals(
                -1L,
                lock.minimumHeight()
        );

        assertEquals(
                1_001_023L,
                lock.minimumTime()
        );
    }

    @Test
    void shouldRejectTimeLockAtBoundary() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertFalse(
                SequenceLocks.evaluate(
                        lock,
                        500L,
                        1_001_023L
                )
        );
    }

    @Test
    void shouldAcceptTimeLockAfterBoundary() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                )
                        )
                );

        assertTrue(
                SequenceLocks.evaluate(
                        lock,
                        500L,
                        1_001_024L
                )
        );
    }

    @Test
    void shouldUseMaximumHeightAcrossMultipleInputs() {

        Transaction transaction =
                transaction(
                        2,
                        List.of(
                                new UInt32(3),
                                new UInt32(7)
                        )
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                ),
                                new InputConfirmation(
                                        200,
                                        2_000_000L
                                )
                        )
                );

        /*
         * input #1:
         * 100 + 3 - 1 = 102
         *
         * input #2:
         * 200 + 7 - 1 = 206
         */
        assertEquals(
                206L,
                lock.minimumHeight()
        );

        assertEquals(
                -1L,
                lock.minimumTime()
        );
    }

    @Test
    void shouldUseMaximumTimeAcrossMultipleInputs() {

        long firstSequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 1L;

        long secondSequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 3L;

        Transaction transaction =
                transaction(
                        2,
                        List.of(
                                new UInt32(firstSequence),
                                new UInt32(secondSequence)
                        )
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                ),
                                new InputConfirmation(
                                        200,
                                        2_000_000L
                                )
                        )
                );

        /*
         * input #1:
         * 1_000_000 + 512 - 1
         * = 1_000_511
         *
         * input #2:
         * 2_000_000 + 1536 - 1
         * = 2_001_535
         */
        assertEquals(
                2_001_535L,
                lock.minimumTime()
        );

        assertEquals(
                -1L,
                lock.minimumHeight()
        );
    }

    @Test
    void shouldHandleMixedHeightAndTimeLocks() {

        long timeSequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        List.of(
                                new UInt32(5),
                                new UInt32(timeSequence)
                        )
                );

        SequenceLock lock =
                SequenceLocks.calculate(
                        transaction,
                        List.of(
                                new InputConfirmation(
                                        100,
                                        1_000_000L
                                ),
                                new InputConfirmation(
                                        200,
                                        2_000_000L
                                )
                        )
                );

        assertEquals(
                104L,
                lock.minimumHeight()
        );

        assertEquals(
                2_001_023L,
                lock.minimumTime()
        );
    }

    @Test
    void shouldRequireBothHeightAndTimeLocksToPass() {

        SequenceLock lock =
                new SequenceLock(
                        104L,
                        1_001_023L
                );

        assertFalse(
                SequenceLocks.evaluate(
                        lock,
                        104L,
                        1_001_024L
                )
        );

        assertFalse(
                SequenceLocks.evaluate(
                        lock,
                        105L,
                        1_001_023L
                )
        );

        assertTrue(
                SequenceLocks.evaluate(
                        lock,
                        105L,
                        1_001_024L
                )
        );
    }

    @Test
    void shouldRejectMismatchedInputConfirmationCount() {

        Transaction transaction =
                transaction(
                        2,
                        List.of(
                                new UInt32(1),
                                new UInt32(2)
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SequenceLocks.calculate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100,
                                                1_000_000L
                                        )
                                )
                        )
        );
    }

    @Test
    void shouldRejectNullInputConfirmation() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(1)
                );

        List<InputConfirmation> confirmations =
                new java.util.ArrayList<>();

        confirmations.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SequenceLocks.calculate(
                                transaction,
                                confirmations
                        )
        );
    }

    private static Transaction transaction(
            int version,
            UInt32 sequence
    ) {
        return transaction(
                version,
                List.of(sequence)
        );
    }

    private static Transaction transaction(
            int version,
            List<UInt32> sequences
    ) {

        List<TxIn> inputs =
                sequences.stream()
                        .map(
                                sequence ->
                                        new TxIn(
                                                new OutPoint(
                                                        Hash256.fromDisplayHex(
                                                                "11".repeat(32)
                                                        ),
                                                        new UInt32(0)
                                                ),
                                                new byte[0],
                                                sequence
                                        )
                        )
                        .toList();

        return new Transaction(
                version,
                inputs,
                List.of(
                        new TxOut(
                                1_000L,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }
}
package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequenceLockValidatorTest {

    @Test
    void shouldNotEnforceBeforeCsvActivation() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(100)
                );

        assertDoesNotThrow(
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                419_327L,
                                                1_000_000L
                                        )
                                ),
                                419_327L,
                                1_000_000L,
                                NetworkParametersRegistry.mainnet()
                        )
        );
    }

    @Test
    void shouldEnforceAtCsvActivationHeight() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(1)
                );

        /*
         * coinHeight = 419328
         * sequence   = 1
         *
         * minimumHeight:
         * 419328 + 1 - 1 = 419328
         *
         * candidate height = 419328
         *
         * 419328 < 419328 == false
         */
        assertThrows(
                TransactionValidationException.class,
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                419_328L,
                                                1_000_000L
                                        )
                                ),
                                419_328L,
                                1_000_001L,
                                NetworkParametersRegistry.mainnet()
                        )
        );
    }

    @Test
    void shouldAcceptHeightBasedSequenceLockAfterBoundary() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(5)
                );

        /*
         * 100 + 5 - 1 = 104
         *
         * candidate block 105 => allowed
         */
        assertDoesNotThrow(
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                105L,
                                2_000_000L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldRejectHeightBasedSequenceLockAtBoundary() {

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(5)
                );

        /*
         * minimumHeight = 104
         * candidate     = 104
         *
         * equality is NOT enough.
         */
        assertThrows(
                TransactionValidationException.class,
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                104L,
                                2_000_000L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldAcceptTimeBasedSequenceLockAfterBoundary() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        /*
         * 2 * 512 = 1024 seconds
         *
         * minimumTime:
         * 1_000_000 + 1024 - 1
         * = 1_001_023
         *
         * previous MTP = 1_001_024 => allowed
         */
        assertDoesNotThrow(
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                200L,
                                1_001_024L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldRejectTimeBasedSequenceLockAtBoundary() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_TYPE_FLAG
                        | 2L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                200L,
                                1_001_023L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldIgnoreSequenceLocksForVersionOneTransaction() {

        Transaction transaction =
                transaction(
                        1,
                        new UInt32(1000)
                );

        assertDoesNotThrow(
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                101L,
                                1_000_001L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldIgnoreInputWithDisableFlag() {

        long sequence =
                SequenceLocks.SEQUENCE_LOCKTIME_DISABLE_FLAG
                        | 50_000L;

        Transaction transaction =
                transaction(
                        2,
                        new UInt32(sequence)
                );

        assertDoesNotThrow(
                () ->
                        SequenceLockValidator.validate(
                                transaction,
                                List.of(
                                        new InputConfirmation(
                                                100L,
                                                1_000_000L
                                        )
                                ),
                                101L,
                                1_000_001L,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    private static Transaction transaction(
            int version,
            UInt32 sequence
    ) {

        OutPoint previousOutput =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "11".repeat(32)
                        ),
                        new UInt32(0)
                );

        return new Transaction(
                version,
                List.of(
                        new TxIn(
                                previousOutput,
                                new byte[0],
                                sequence
                        )
                ),
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
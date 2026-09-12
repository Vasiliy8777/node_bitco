package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;

public final class TransactionFinality {

    public static final long LOCKTIME_THRESHOLD =
            500_000_000L;

    private static final long SEQUENCE_FINAL =
            TxIn.FINAL_SEQUENCE.value();

    private TransactionFinality() {
    }

    public static boolean isFinal(
            Transaction transaction,
            long blockHeight,
            long blockTime
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (blockTime < 0) {
            throw new IllegalArgumentException(
                    "blockTime must not be negative"
            );
        }

        long lockTime =
                transaction.lockTime().value();

        if (lockTime == 0) {
            return true;
        }

        long comparisonValue =
                lockTime < LOCKTIME_THRESHOLD
                        ? blockHeight
                        : blockTime;

        if (lockTime < comparisonValue) {
            return true;
        }

        /*
         * nLockTime влияет на транзакцию только если хотя бы
         * один input имеет non-final sequence.
         *
         * Если все sequence == 0xFFFFFFFF,
         * lockTime отключён.
         */
        for (TxIn input : transaction.inputs()) {

            if (input.sequence().value()
                    != SEQUENCE_FINAL) {

                return false;
            }
        }

        return true;
    }

    public static void validate(
            Transaction transaction,
            long blockHeight,
            long blockTime
    ) {
        if (!isFinal(
                transaction,
                blockHeight,
                blockTime
        )) {
            throw new TransactionValidationException(
                    "Transaction is non-final"
            );
        }
    }
}
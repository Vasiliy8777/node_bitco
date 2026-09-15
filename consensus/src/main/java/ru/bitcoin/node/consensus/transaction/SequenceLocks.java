package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;

import java.util.List;

public final class SequenceLocks {

    public static final long SEQUENCE_LOCKTIME_DISABLE_FLAG =
            1L << 31;

    public static final long SEQUENCE_LOCKTIME_TYPE_FLAG =
            1L << 22;

    public static final long SEQUENCE_LOCKTIME_MASK =
            0x0000FFFFL;

    public static final int SEQUENCE_LOCKTIME_GRANULARITY =
            9;

    private SequenceLocks() {
    }

    public static SequenceLock calculate(
            Transaction transaction,
            List<InputConfirmation> inputs
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (inputs == null) {
            throw new IllegalArgumentException(
                    "inputs must not be null"
            );
        }

        if (transaction.inputs().size()
                != inputs.size()) {
            throw new IllegalArgumentException(
                    "Input confirmation count must match "
                            + "transaction input count"
            );
        }

        /*
         * BIP68 применяется только к transaction version >= 2.
         */
        if (Integer.toUnsignedLong(transaction.version()) < 2) {
            return SequenceLock.NONE;
        }

        long minimumHeight = -1L;
        long minimumTime = -1L;

        for (int i = 0;
             i < transaction.inputs().size();
             i++) {

            TxIn input =
                    transaction.inputs().get(i);

            InputConfirmation confirmation =
                    inputs.get(i);

            if (confirmation == null) {
                throw new IllegalArgumentException(
                        "Input confirmation must not be null"
                );
            }

            long sequence =
                    input.sequence().value();

            /*
             * Bit 31 disables relative lock-time
             * for this input.
             */
            if ((sequence
                    & SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0) {
                continue;
            }

            long relativeLock =
                    sequence
                            & SEQUENCE_LOCKTIME_MASK;

            if ((sequence
                    & SEQUENCE_LOCKTIME_TYPE_FLAG) != 0) {

                /*
                 * Time-based relative lock.
                 *
                 * BIP68 unit = 512 seconds.
                 *
                 * Bitcoin Core stores the last INVALID time:
                 *
                 * coinMTP + relativeTime - 1
                 */
                long relativeSeconds =
                        relativeLock
                                << SEQUENCE_LOCKTIME_GRANULARITY;

                long candidate =
                        Math.addExact(
                                confirmation.previousMedianTimePast(),
                                relativeSeconds
                        ) - 1L;

                minimumTime =
                        Math.max(
                                minimumTime,
                                candidate
                        );

            } else {

                /*
                 * Height-based relative lock.
                 *
                 * Core stores the last INVALID height:
                 *
                 * coinHeight + relativeHeight - 1
                 */
                long candidate =
                        Math.addExact(
                                confirmation.height(),
                                relativeLock
                        ) - 1L;

                minimumHeight =
                        Math.max(
                                minimumHeight,
                                candidate
                        );
            }
        }

        return new SequenceLock(
                minimumHeight,
                minimumTime
        );
    }

    public static boolean evaluate(
            SequenceLock lock,
            long blockHeight,
            long previousMedianTimePast
    ) {
        if (lock == null) {
            throw new IllegalArgumentException(
                    "lock must not be null"
            );
        }

        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }

        /*
         * Bitcoin Core:
         *
         * if (lock.height >= blockHeight) fail
         * if (lock.time >= previousMTP) fail
         */
        return lock.minimumHeight() < blockHeight
                && lock.minimumTime()
                < previousMedianTimePast;
    }
}

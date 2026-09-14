package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

public final class TransactionWeight {

    private static final long WITNESS_SCALE_FACTOR = 4L;

    private TransactionWeight() {
    }

    public static long calculate(
            Transaction transaction
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        long strippedSize =
                TransactionSerializer.serializeLegacy(
                        transaction
                ).length;

        long totalSize =
                TransactionSerializer.serialize(
                        transaction
                ).length;

        /*
         * BIP141:
         *
         * weight =
         *     strippedSize * 4
         *     + witnessSize
         *
         * witnessSize =
         *     totalSize - strippedSize
         *
         * Поэтому:
         *
         * weight =
         *     strippedSize * 3
         *     + totalSize
         */
        return strippedSize
                * (WITNESS_SCALE_FACTOR - 1)
                + totalSize;
    }

    public static long virtualSize(
            Transaction transaction
    ) {
        return virtualSize(
                calculate(transaction)
        );
    }

    public static long virtualSize(
            long weight
    ) {
        if (weight < 0) {
            throw new IllegalArgumentException(
                    "weight must not be negative"
            );
        }

        /*
         * ceil(weight / 4)
         */
        return (weight + WITNESS_SCALE_FACTOR - 1)
                / WITNESS_SCALE_FACTOR;
    }
}
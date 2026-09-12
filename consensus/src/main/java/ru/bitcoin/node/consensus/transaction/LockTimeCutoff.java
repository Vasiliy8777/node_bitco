package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class LockTimeCutoff {

    private LockTimeCutoff() {
    }

    public static long calculate(
            long blockHeight,
            long blockTimestamp,
            long previousMedianTimePast,
            NetworkParameters networkParameters
    ) {
        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (blockTimestamp < 0) {
            throw new IllegalArgumentException(
                    "blockTimestamp must not be negative"
            );
        }

        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        /*
         * До BIP113 absolute nLockTime сравнивался
         * со временем текущего блока.
         *
         * После активации BIP113 используется MTP
         * предыдущего блока.
         */
        if (blockHeight < networkParameters.csvHeight()) {
            return blockTimestamp;
        }

        return previousMedianTimePast;
    }
}
package ru.bitcoin.node.consensus.money;

import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class BlockSubsidy {

    private static final long INITIAL_SUBSIDY =
            50L * Money.SATOSHIS_PER_BTC;

    private BlockSubsidy() {
    }

    public static long calculate(
            long blockHeight,
            NetworkParameters parameters
    ) {
        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        long halvings =
                blockHeight
                        / parameters.subsidyHalvingInterval();

        if (halvings >= 64) {
            return 0L;
        }

        return INITIAL_SUBSIDY >> halvings;
    }
}
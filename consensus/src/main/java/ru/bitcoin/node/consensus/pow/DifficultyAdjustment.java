package ru.bitcoin.node.consensus.pow;

import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;

public final class DifficultyAdjustment {

    private DifficultyAdjustment() {
    }

    public static BigInteger calculateNextTarget(
            BigInteger previousTarget,
            long actualTimespanSeconds,
            NetworkParameters parameters
    ) {
        if (previousTarget == null) {
            throw new IllegalArgumentException(
                    "previousTarget must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        if (previousTarget.signum() <= 0) {
            throw new IllegalArgumentException(
                    "previousTarget must be positive"
            );
        }

        if (parameters.noRetargeting()) {
            return previousTarget;
        }

        long targetTimespan =
                parameters.targetTimespanSeconds();

        long minimumTimespan =
                targetTimespan / 4;

        long maximumTimespan =
                targetTimespan * 4;

        long boundedTimespan =
                Math.max(
                        minimumTimespan,
                        Math.min(
                                actualTimespanSeconds,
                                maximumTimespan
                        )
                );

        BigInteger newTarget =
                previousTarget
                        .multiply(
                                BigInteger.valueOf(
                                        boundedTimespan
                                )
                        )
                        .divide(
                                BigInteger.valueOf(
                                        targetTimespan
                                )
                        );

        if (newTarget.compareTo(
                parameters.powLimit()
        ) > 0) {
            newTarget =
                    parameters.powLimit();
        }

        return newTarget;
    }

    public static long calculateNextBits(
            long previousBits,
            long actualTimespanSeconds,
            NetworkParameters parameters
    ) {
        BigInteger previousTarget =
                CompactTarget.decode(
                        previousBits
                );

        BigInteger newTarget =
                calculateNextTarget(
                        previousTarget,
                        actualTimespanSeconds,
                        parameters
                );

        return CompactTarget.encode(
                newTarget
        );
    }
}
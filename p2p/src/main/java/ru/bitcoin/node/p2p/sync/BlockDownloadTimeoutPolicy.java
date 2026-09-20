package ru.bitcoin.node.p2p.sync;

import java.time.Duration;
import java.util.Objects;

public final class BlockDownloadTimeoutPolicy {

    private final Duration targetSpacing;

    public BlockDownloadTimeoutPolicy(
            Duration targetSpacing
    ) {

        this.targetSpacing =
                requirePositive(
                        targetSpacing
                );
    }

    public Duration targetSpacing() {
        return targetSpacing;
    }

    /**
     * Calculates the maximum time allowed for the current
     * first block in a peer's in-flight download queue.
     *
     * Formula:
     *
     * targetSpacing * (1 + 0.5 * otherDownloadingPeers)
     *
     * Using integer arithmetic:
     *
     * targetSpacing * (2 + otherDownloadingPeers) / 2
     */
    public Duration timeout(
            int otherDownloadingPeers
    ) {

        if (otherDownloadingPeers < 0) {
            throw new IllegalArgumentException(
                    "otherDownloadingPeers must not be negative"
            );
        }

        long targetNanos =
                targetSpacing.toNanos();

        long multiplier =
                Math.addExact(
                        2L,
                        otherDownloadingPeers
                );

        long timeoutNanos =
                Math.multiplyExact(
                        targetNanos,
                        multiplier
                ) / 2L;

        return Duration.ofNanos(
                timeoutNanos
        );
    }

    public boolean isTimedOut(
            Duration downloadingAge,
            int otherDownloadingPeers
    ) {

        Objects.requireNonNull(
                downloadingAge,
                "downloadingAge"
        );

        if (downloadingAge.isNegative()) {
            throw new IllegalArgumentException(
                    "downloadingAge must not be negative"
            );
        }

        Duration timeout =
                timeout(
                        otherDownloadingPeers
                );

        return downloadingAge.compareTo(
                timeout
        ) > 0;
    }

    private static Duration requirePositive(
            Duration duration
    ) {

        Objects.requireNonNull(
                duration,
                "targetSpacing"
        );

        if (duration.isZero()
                || duration.isNegative()) {

            throw new IllegalArgumentException(
                    "targetSpacing must be positive"
            );
        }

        return duration;
    }
}
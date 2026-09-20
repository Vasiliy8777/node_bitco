package ru.bitcoin.node.p2p.sync;

import java.time.Duration;
import java.util.Objects;

public final class BlockDownloadStallTimeoutPolicy {

    /*
     * Bitcoin Core:
     *
     * BLOCK_STALLING_TIMEOUT_DEFAULT = 2s
     * BLOCK_STALLING_TIMEOUT_MAX     = 64s
     */
    public static final Duration DEFAULT_TIMEOUT =
            Duration.ofSeconds(
                    2
            );

    public static final Duration MAX_TIMEOUT =
            Duration.ofSeconds(
                    64
            );

    private Duration timeout;

    public BlockDownloadStallTimeoutPolicy() {
        this(
                DEFAULT_TIMEOUT
        );
    }

    BlockDownloadStallTimeoutPolicy(
            Duration initialTimeout
    ) {

        Objects.requireNonNull(
                initialTimeout,
                "initialTimeout"
        );

        if (initialTimeout.isZero()
                || initialTimeout.isNegative()) {

            throw new IllegalArgumentException(
                    "initialTimeout must be positive"
            );
        }

        if (initialTimeout.compareTo(
                MAX_TIMEOUT
        ) > 0) {

            throw new IllegalArgumentException(
                    "initialTimeout must not exceed "
                            + MAX_TIMEOUT
            );
        }

        timeout =
                initialTimeout;
    }

    public synchronized Duration timeout() {
        return timeout;
    }

    public synchronized boolean isTimedOut(
            Duration stallingAge
    ) {

        Objects.requireNonNull(
                stallingAge,
                "stallingAge"
        );

        if (stallingAge.isNegative()) {
            throw new IllegalArgumentException(
                    "stallingAge must not be negative"
            );
        }

        /*
         * Bitcoin Core checks:
         *
         * m_stalling_since < current_time - timeout
         *
         * Therefore equality with the timeout boundary
         * does NOT disconnect the peer.
         */
        return stallingAge.compareTo(
                timeout
        ) > 0;
    }

    public synchronized void increaseAfterTimeout() {

        Duration doubled;

        try {

            doubled =
                    timeout.multipliedBy(
                            2
                    );

        } catch (ArithmeticException exception) {

            doubled =
                    MAX_TIMEOUT;
        }

        if (doubled.compareTo(
                MAX_TIMEOUT
        ) > 0) {

            timeout =
                    MAX_TIMEOUT;

        } else {

            timeout =
                    doubled;
        }
    }
}
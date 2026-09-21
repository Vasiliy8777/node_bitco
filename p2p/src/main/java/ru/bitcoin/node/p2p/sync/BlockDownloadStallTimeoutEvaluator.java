package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.p2p.Peer;

import java.time.Duration;
import java.util.Objects;

public final class BlockDownloadStallTimeoutEvaluator {

    private final BlockDownloadStallTracker stallTracker;
    private final BlockDownloadStallTimeoutPolicy timeoutPolicy;

    public BlockDownloadStallTimeoutEvaluator(
            BlockDownloadStallTracker stallTracker,
            BlockDownloadStallTimeoutPolicy timeoutPolicy
    ) {

        this.stallTracker =
                Objects.requireNonNull(
                        stallTracker,
                        "stallTracker"
                );

        this.timeoutPolicy =
                Objects.requireNonNull(
                        timeoutPolicy,
                        "timeoutPolicy"
                );
    }

    public Evaluation evaluate() {

        Peer peer =
                stallTracker.stallingPeer();

        if (peer == null) {

            return new Evaluation(
                    false,
                    false,
                    null,
                    Duration.ZERO,
                    Duration.ZERO
            );
        }

        Duration stallingAge =
                Duration.ofNanos(
                        stallTracker.stallingAgeNanos()
                );

        Duration timeout =
                timeoutPolicy.timeout();

        boolean timedOut =
                timeoutPolicy.isTimedOut(
                        stallingAge
                );

        return new Evaluation(
                true,
                timedOut,
                peer,
                stallingAge,
                timeout
        );
    }

    public void timeoutHandled() {
        timeoutPolicy.increaseAfterTimeout();
    }

    public record Evaluation(
            boolean stalling,
            boolean timedOut,
            Peer peer,
            Duration stallingAge,
            Duration timeout
    ) {

        public Evaluation {

            Objects.requireNonNull(
                    stallingAge,
                    "stallingAge"
            );

            Objects.requireNonNull(
                    timeout,
                    "timeout"
            );

            if (stallingAge.isNegative()) {
                throw new IllegalArgumentException(
                        "stallingAge must not be negative"
                );
            }

            if (timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "timeout must not be negative"
                );
            }

            if (!stalling) {

                if (peer != null) {
                    throw new IllegalArgumentException(
                            "Non-stalling evaluation must not contain a peer"
                    );
                }

                if (timedOut) {
                    throw new IllegalArgumentException(
                            "Non-stalling evaluation must not be timed out"
                    );
                }

                if (!stallingAge.isZero()) {
                    throw new IllegalArgumentException(
                            "Non-stalling evaluation must have zero stalling age"
                    );
                }

                if (!timeout.isZero()) {
                    throw new IllegalArgumentException(
                            "Non-stalling evaluation must have zero timeout"
                    );
                }

            } else {

                Objects.requireNonNull(
                        peer,
                        "peer"
                );

                if (timeout.isZero()) {
                    throw new IllegalArgumentException(
                            "Stalling evaluation must have positive timeout"
                    );
                }
            }
        }
    }
}
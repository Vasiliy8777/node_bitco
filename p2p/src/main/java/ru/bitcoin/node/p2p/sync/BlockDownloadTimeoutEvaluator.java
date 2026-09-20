package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.p2p.Peer;

import java.time.Duration;
import java.util.Objects;

public final class BlockDownloadTimeoutEvaluator {

    private final BlockInFlightTracker inFlightTracker;
    private final BlockDownloadTimeoutPolicy timeoutPolicy;

    public BlockDownloadTimeoutEvaluator(
            BlockInFlightTracker inFlightTracker,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {

        this.inFlightTracker =
                Objects.requireNonNull(
                        inFlightTracker,
                        "inFlightTracker"
                );

        this.timeoutPolicy =
                Objects.requireNonNull(
                        timeoutPolicy,
                        "timeoutPolicy"
                );
    }

    public Evaluation evaluate(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        if (inFlightTracker.count(peer) == 0) {
            return Evaluation.notDownloading();
        }

        int otherDownloadingPeers =
                inFlightTracker.otherDownloadingPeerCount(
                        peer
                );

        Duration downloadingAge =
                Duration.ofNanos(
                        inFlightTracker.downloadingAgeNanos(
                                peer
                        )
                );

        Duration timeout =
                timeoutPolicy.timeout(
                        otherDownloadingPeers
                );

        boolean timedOut =
                timeoutPolicy.isTimedOut(
                        downloadingAge,
                        otherDownloadingPeers
                );

        return new Evaluation(
                true,
                timedOut,
                downloadingAge,
                timeout
        );
    }

    public record Evaluation(
            boolean downloading,
            boolean timedOut,
            Duration downloadingAge,
            Duration timeout
    ) {

        public Evaluation {

            Objects.requireNonNull(
                    downloadingAge,
                    "downloadingAge"
            );

            Objects.requireNonNull(
                    timeout,
                    "timeout"
            );

            if (downloadingAge.isNegative()) {
                throw new IllegalArgumentException(
                        "downloadingAge must not be negative"
                );
            }

            if (timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "timeout must not be negative"
                );
            }

            if (!downloading
                    && (timedOut
                    || !downloadingAge.isZero()
                    || !timeout.isZero())) {

                throw new IllegalArgumentException(
                        "Non-downloading evaluation must have zero age, "
                                + "zero timeout and timedOut=false"
                );
            }
        }

        public static Evaluation notDownloading() {

            return new Evaluation(
                    false,
                    false,
                    Duration.ZERO,
                    Duration.ZERO
            );
        }
    }
}
package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.p2p.Peer;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class BlockDownloadStallTracker {

    private final LongSupplier nanoTime;

    private Peer stallingPeer;
    private long stallingSinceNanos;

    public BlockDownloadStallTracker() {
        this(
                System::nanoTime
        );
    }

    public BlockDownloadStallTracker(
            LongSupplier nanoTime
    ) {

        this.nanoTime =
                Objects.requireNonNull(
                        nanoTime,
                        "nanoTime"
                );
    }

    public synchronized void update(
            Peer peer
    ) {

        /*
         * No peer currently blocks ordered progress.
         *
         * Any previous stall period therefore ends.
         */
        if (peer == null) {

            stallingPeer =
                    null;

            stallingSinceNanos =
                    0L;

            return;
        }

        /*
         * The same peer is still blocking progress.
         *
         * Do not restart its timer.
         */
        if (stallingPeer == peer) {
            return;
        }

        /*
         * Either stall tracking has just started,
         * or a different peer has become the blocker.
         *
         * Both cases begin a new continuous stall period.
         */
        stallingPeer =
                peer;

        stallingSinceNanos =
                nanoTime.getAsLong();
    }

    public synchronized boolean isStalling() {
        return stallingPeer != null;
    }

    public synchronized boolean isStalling(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        return stallingPeer == peer;
    }

    public synchronized Peer stallingPeer() {
        return stallingPeer;
    }

    public synchronized long stallingSinceNanos() {

        if (stallingPeer == null) {
            throw new IllegalStateException(
                    "No peer is currently stalling"
            );
        }

        return stallingSinceNanos;
    }

    public synchronized long stallingAgeNanos() {

        if (stallingPeer == null) {
            throw new IllegalStateException(
                    "No peer is currently stalling"
            );
        }

        return Math.max(
                0L,
                nanoTime.getAsLong()
                        - stallingSinceNanos
        );
    }

    public synchronized void clear(
            Peer peer
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        /*
         * Clearing an unrelated peer must not
         * disturb the actual stalling peer.
         */
        if (stallingPeer != peer) {
            return;
        }

        stallingPeer =
                null;

        stallingSinceNanos =
                0L;
    }

    public synchronized void clear() {

        stallingPeer =
                null;

        stallingSinceNanos =
                0L;
    }
}
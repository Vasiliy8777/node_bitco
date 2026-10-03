package ru.bitcoin.node.p2p.sync;

import ru.bitcoin.node.p2p.Peer;

import java.time.Duration;
import java.util.Objects;

/**
 * Unified Core-style per-peer block download state owned by one scheduler session.
 */
public final class PeerBlockDownloadState {
    private final Peer peer;
    private long bestKnownHeight = -1;
    private long lastCommonHeight = -1;
    private long downloadingSinceNanos;
    private long stallingSinceNanos;
    private long pausedUntilNanos;
    private long completedBlocks;
    private long failedBlocks;
    private long latencyEwmaNanos = Duration.ofMillis(250).toNanos();

    public PeerBlockDownloadState(Peer peer) {
        this.peer = Objects.requireNonNull(peer);
        refreshBestKnown();
    }

    public void refreshBestKnown() {
        try {
            if (peer.remoteVersion() != null)
                bestKnownHeight = Math.max(bestKnownHeight, peer.remoteVersion().startHeight());
        } catch (RuntimeException ignored) {
        }
    }

    public boolean canServe(Long height, long now) {
        refreshBestKnown();
        /*
         * VERSION.start_height is informational and is not an upper bound on
         * the historical blocks a NODE_NETWORK peer can serve. History
         * eligibility is decided centrally by LimitedHistoryPeerPolicy in
         * SchedulerBlockDownloadSession.peerCanServe(). This state object only
         * contributes temporary per-peer scheduling/cooldown state.
         */
        return now >= pausedUntilNanos;
    }

    public void assigned(long now) {
        if (downloadingSinceNanos == 0) downloadingSinceNanos = now;
    }

    public void completed(Long height, long latency) {
        completedBlocks++;
        downloadingSinceNanos = 0;
        stallingSinceNanos = 0;
        if (height != null) lastCommonHeight = Math.max(lastCommonHeight, height);
        latencyEwmaNanos = (latencyEwmaNanos * 7 + Math.max(1, latency)) / 8;
    }

    public void failed(long now) {
        failedBlocks++;
        downloadingSinceNanos = 0;
        if (stallingSinceNanos == 0) stallingSinceNanos = now;
    }

    public void stall(long now, Duration cooldown) {
        if (stallingSinceNanos == 0) stallingSinceNanos = now;
        pausedUntilNanos = Math.max(pausedUntilNanos, now + cooldown.toNanos());
    }

    public long score(int inFlight) {
        return latencyEwmaNanos * (long) (inFlight + 1) + failedBlocks * Duration.ofMillis(100).toNanos();
    }

    public long bestKnownHeight() {
        return bestKnownHeight;
    }

    public long lastCommonHeight() {
        return lastCommonHeight;
    }

    public long stallingSinceNanos() {
        return stallingSinceNanos;
    }

    public long pausedUntilNanos() {
        return pausedUntilNanos;
    }
}

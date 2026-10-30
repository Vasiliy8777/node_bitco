package ru.bitcoin.node.rvn.app.sync;

import ru.bitcoin.node.rvn.app.chain.RvnAssetsDeployment;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.p2p.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.storage.*;
import java.io.*;

/** Downloads commitment-checked candidate bodies. Never advances a UTXO/validated-block tip. */
public final class RvnBlockDownloader {
    private final RvnHeaderStore store;
    private final long timeoutMs;
    private int inFlight;
    public record Timing(long transportMs, long checkStoreMs, long bytes) {}
    private Timing lastTiming = new Timing(0, 0, 0);
    public Timing lastTiming() { return lastTiming; }
    private final RvnAssetsDeployment assets = new RvnAssetsDeployment();
    public RvnBlockDownloader(RvnHeaderStore store, long timeoutMs) {
        this(store, timeoutMs, 4);
    }
    public RvnBlockDownloader(RvnHeaderStore store, long timeoutMs, int inFlight) {
        if (timeoutMs < 1 || timeoutMs > 120000) throw new IllegalArgumentException("RVN block timeout");
        if (inFlight < 1 || inFlight > 32) throw new IllegalArgumentException("RVN block in-flight limit");
        this.store = java.util.Objects.requireNonNull(store); this.timeoutMs = timeoutMs; this.inFlight = inFlight;
    }
    public int downloadBatch(RvnPeerSession session, int limit) throws IOException, InterruptedException {
        if (limit < 1 || limit > 512) throw new IllegalArgumentException("RVN block batch limit");
        var requested = new java.util.ArrayList<RvnBlockClient.Expected>();
        synchronized (store) {
            var first = store.nextMissingBlockBody();
            if (first == null) return 0;
            var best = store.best();
            for (long height = first.height(); height <= best.height() && requested.size() < limit; height++) {
                var next = store.ancestor(best, height);
                if (!store.hasBlockBody(next.id())) requested.add(new RvnBlockClient.Expected(next.id().wire(),
                        RvnBlockHeader.parse(next.raw(), RvnActivation.MAINNET_KAWPOW_TIME)));
            }
        }
        int[] added = {0};
        long[] processing = {0}, bytesReceived = {0};
        class StagedBodies {
            final java.util.Map<ru.bitcoin.node.rvn.consensus.RvnHash256, byte[]> bodies = new java.util.LinkedHashMap<>();
            long bytes;
            void flush() throws IOException {
                if (bodies.isEmpty()) return;
                long processingStart = System.nanoTime();
                try {
                    added[0] += store.saveDownloadedBodies(bodies, (payload, header) -> {
                        boolean active = assets.activeForNext(store.require(header.parentId()), store);
                        RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(payload), active);
                    });
                    bodies.clear(); bytes = 0;
                } finally { processing[0] += System.nanoTime() - processingStart; }
            }
        }
        var staged = new StagedBodies();
        long requestStart = System.nanoTime();
        try {
            RvnBlockClient.requestBatch(session, requested, timeoutMs, inFlight, (next, payload) -> {
                // Large bodies commit earlier; historical small bodies share a
                // flush. Staging remains bounded independently of queue limits.
                if (staged.bytes + payload.length > 8L * 1024 * 1024) staged.flush();
                staged.bodies.put(new ru.bitcoin.node.rvn.consensus.RvnHash256(next.id()), payload);
                staged.bytes += payload.length; bytesReceived[0] += payload.length;
                if (staged.bodies.size() >= 128) staged.flush();
            });
        } finally {
            // Preserve received bodies on peer loss. Interrupted shutdown can
            // discard only the bounded, unpublished staging buffer.
            if (!Thread.currentThread().isInterrupted()) staged.flush();
        }
        long total = System.nanoTime() - requestStart;
        lastTiming = new Timing(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0, total - processing[0])),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(processing[0]), bytesReceived[0]);
        store.nextMissingBlockBody(); // Advance the contiguous cursor after out-of-order replies.
        return added[0];
    }
    public long downloadedHeight() throws IOException { return store.downloadedBodyTip().height(); }
    public int inFlight() { return inFlight; }
    /** Larger windows never relax the transport's byte budget. Retry with a
     * smaller window if large legitimate blocks filled that bounded queue. */
    public boolean reduceWindowAfterBudgetFailure(IOException failure) {
        if (failure == null || failure.getMessage() == null || !failure.getMessage().contains("byte/count limit") || inFlight <= 4)
            return false;
        inFlight = Math.max(4, inFlight / 2); return true;
    }
}

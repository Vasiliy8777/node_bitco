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
    private final RvnAssetsDeployment assets = new RvnAssetsDeployment();
    public RvnBlockDownloader(RvnHeaderStore store, long timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 120000) throw new IllegalArgumentException("RVN block timeout");
        this.store = java.util.Objects.requireNonNull(store); this.timeoutMs = timeoutMs;
    }
    public int downloadBatch(RvnPeerSession session, int limit) throws IOException, InterruptedException {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("RVN block batch limit");
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
        RvnBlockClient.requestBatch(session, requested, timeoutMs, (next, payload) -> {
            if (store.saveDownloadedBody(new ru.bitcoin.node.rvn.consensus.RvnHash256(next.id()), payload, (bytes, header) -> {
                boolean active = assets.activeForNext(store.require(header.parentId()), store);
                RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(bytes), active);
            })) added[0]++;
        });
        store.nextMissingBlockBody(); // Advance the contiguous cursor after out-of-order replies.
        return added[0];
    }
    public long downloadedHeight() throws IOException { return store.downloadedBodyTip().height(); }
}

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
        int added = 0;
        for (int i = 0; i < limit; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("RVN block download interrupted");
            var next = store.nextMissingBlockBody();
            if (next == null) break;
            byte[] payload = RvnBlockClient.request(session, next.id().wire(), RvnBlockHeader.parse(next.raw(),
                    RvnActivation.MAINNET_KAWPOW_TIME), timeoutMs);
            if (store.saveDownloadedBody(next.id(), payload, (bytes, header) -> {
                boolean active = assets.activeForNext(store.require(header.parentId()), store);
                RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(bytes), active);
            })) added++;
        }
        return added;
    }
    public long downloadedHeight() throws IOException { return store.downloadedBodyTip().height(); }
}

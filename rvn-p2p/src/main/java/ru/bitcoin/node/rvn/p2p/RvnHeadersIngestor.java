package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;

import java.io.IOException;
import java.util.*;

/**
 * Stores received headers as UNVERIFIED batches; no chain-tip mutation.
 */
public final class RvnHeadersIngestor {
    private final RvnUnverifiedHeaderSpool spool;
    private final long activationTime;
    private long batchesReceived, headersReceived, emptyResponses;

    public RvnHeadersIngestor(RvnUnverifiedHeaderSpool spool, long activationTime) {
        this.spool = Objects.requireNonNull(spool);
        this.activationTime = activationTime;
    }

    public synchronized Receipt accept(List<RvnBlockHeader> headers) throws IOException {
        Objects.requireNonNull(headers);
        RvnHeadersBatch batch = RvnHeadersBatch.fromHeaders(headers, activationTime);
        if (batch.count() == 0) {
            emptyResponses++;
            return new Receipt(null, 0, false);
        }
        String id = spool.store(batch);
        batchesReceived++;
        headersReceived += batch.count();
        return new Receipt(id, batch.count(), true);
    }

    public synchronized Metrics metrics() {
        return new Metrics(batchesReceived, headersReceived, emptyResponses);
    }

    public record Receipt(String contentId, int headerCount, boolean stored) {
    }

    public record Metrics(long batchesReceived, long headersReceived, long emptyResponses) {
    }
}

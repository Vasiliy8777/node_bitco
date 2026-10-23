package ru.bitcoin.node.rvn.protocol;

import java.io.*;
import java.util.*;

/**
 * Immutable, UNVERIFIED received batch; not a chain tip or consensus result.
 */
public final class RvnHeadersBatch {
    private final List<RvnBlockHeader> headers;
    private final byte[] payload;
    private final byte[] contentDigest;

    private RvnHeadersBatch(List<RvnBlockHeader> headers, byte[] payload) {
        this.headers = List.copyOf(headers);
        this.payload = payload.clone();
        this.contentDigest = RvnWire.doubleSha(payload);
    }

    public static RvnHeadersBatch fromPayload(byte[] payload, long activationTime) throws IOException {
        List<RvnBlockHeader> headers = RvnHeaders.decode(payload, activationTime);
        RvnHeaderRules.checkBatch(headers);
        return new RvnHeadersBatch(headers, payload);
    }

    public static RvnHeadersBatch fromHeaders(List<RvnBlockHeader> headers, long activationTime)
            throws IOException {
        return fromPayload(RvnHeaders.encode(headers), activationTime);
    }

    public List<RvnBlockHeader> headers() {
        return headers;
    }

    public int count() {
        return headers.size();
    }

    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Digest of the wire message payload, NEVER a Ravencoin block hash.
     */
    public byte[] contentDigest() {
        return contentDigest.clone();
    }
}

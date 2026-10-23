package ru.bitcoin.node.rvn.consensus;

import java.util.Objects;

/**
 * Explicit, network-configured wire format boundary.
 * Does not establish that the network activated KawPoW or verify PoW.
 */
public final class RvnHeaderFormatPolicy {
    private final long activationTimestamp;

    public RvnHeaderFormatPolicy(long activationTimestamp) {
        if (activationTimestamp < 0 || activationTimestamp > 0xffff_ffffL)
            throw new IllegalArgumentException("activationTimestamp");
        this.activationTimestamp = activationTimestamp;
    }

    public long activationTimestamp() {
        return activationTimestamp;
    }

    public void requireFormat(byte[] raw) {
        RvnHeaderWireFields fields = new RvnHeaderWireFields(raw);
        boolean expectedKawpow = fields.timestamp() >= activationTimestamp;
        if (fields.kawpow() != expectedKawpow)
            throw new SecurityException("RVN header length inconsistent with configured activation timestamp");
    }

    public void requireFormat(RvnHeaderCandidate candidate, byte[] raw) {
        Objects.requireNonNull(candidate);
        requireFormat(raw);
        new RvnHeaderWireFields(raw).requireMatches(candidate);
    }
}

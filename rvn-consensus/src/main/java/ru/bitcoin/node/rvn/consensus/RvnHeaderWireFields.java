package ru.bitcoin.node.rvn.consensus;

import java.util.Arrays;

/**
 * Decode only fields shared by Ravencoin wire headers. No hash or PoW computation.
 */
public final class RvnHeaderWireFields {
    private final byte[] raw;
    private final boolean kawpow;

    public RvnHeaderWireFields(byte[] raw) {
        if (raw == null || (raw.length != 80 && raw.length != 120))
            throw new IllegalArgumentException("Expected 80 or 120 header bytes");
        this.raw = raw.clone();
        this.kawpow = raw.length == 120;
    }

    private long uint32(int off) {
        return (raw[off] & 255L) | ((raw[off + 1] & 255L) << 8)
                | ((raw[off + 2] & 255L) << 16) | ((raw[off + 3] & 255L) << 24);
    }

    public boolean kawpow() {
        return kawpow;
    }

    public RvnHash256 parentId() {
        return new RvnHash256(Arrays.copyOfRange(raw, 4, 36));
    }

    public long timestamp() {
        return uint32(68);
    }

    public long bits() {
        return uint32(72);
    }

    public long declaredHeight() {
        return kawpow ? uint32(76) : -1;
    }

    public byte[] raw() {
        return raw.clone();
    }

    public void requireMatches(RvnHeaderCandidate candidate) {
        if (candidate == null || !candidate.parentId().equals(parentId())
                || candidate.kawpow() != kawpow || candidate.timestamp() != timestamp()
                || candidate.bits() != bits() || candidate.declaredHeight() != declaredHeight())
            throw new SecurityException("Candidate metadata disagrees with wire header");
    }
}

package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * Defensive, byte-accurate 120-byte KawPoW header field view.
 */
public final class RvnKawpowHeaderView {
    private final byte[] raw;

    public RvnKawpowHeaderView(byte[] raw) {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.KAWPOW, raw);
        this.raw = raw.clone();
    }

    public long declaredHeight() {
        return RvnPowHeaderRules.kawpowDeclaredHeight(raw);
    }

    public long epoch() {
        return RvnKawpowEpoch.fromHeight(declaredHeight());
    }

    public long nonceBits() {
        return Integer.toUnsignedLong(RvnKawpowWords.readLE(raw, 80)) |
                (Integer.toUnsignedLong(RvnKawpowWords.readLE(raw, 84)) << 32);
    }

    public byte[] mixHashWire() {
        return Arrays.copyOfRange(raw, 88, 120);
    }

    public byte[] raw() {
        return raw.clone();
    }
}

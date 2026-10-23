package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Structural prerequisites; algorithm-specific validation remains in the engine.
 */
public final class RvnPowHeaderRules {
    private RvnPowHeaderRules() {
    }

    public static void requireHeader(RvnPowAlgorithm algorithm, byte[] raw) {
        Objects.requireNonNull(algorithm);
        if (raw == null || raw.length != (algorithm == RvnPowAlgorithm.KAWPOW ? 120 : 80))
            throw new IllegalArgumentException("Incorrect Ravencoin header length for " + algorithm);
    }

    public static long unsigned32(byte[] raw, int offset) {
        if (raw == null || offset < 0 || offset > raw.length - 4)
            throw new IllegalArgumentException("u32 offset");
        return (raw[offset] & 255L) | ((raw[offset + 1] & 255L) << 8) |
                ((raw[offset + 2] & 255L) << 16) | ((raw[offset + 3] & 255L) << 24);
    }

    public static long kawpowDeclaredHeight(byte[] raw) {
        requireHeader(RvnPowAlgorithm.KAWPOW, raw);
        return unsigned32(raw, 76);
    }
}

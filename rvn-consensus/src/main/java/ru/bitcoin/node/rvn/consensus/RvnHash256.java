package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Immutable 256-bit identifier in wire byte order; NOT a hashing algorithm.
 */
public final class RvnHash256 implements Comparable<RvnHash256> {
    private final byte[] wire;

    public RvnHash256(byte[] wire) {
        if (wire == null || wire.length != 32) throw new IllegalArgumentException("hash must be 32 bytes");
        this.wire = wire.clone();
    }

    public byte[] wire() {
        return wire.clone();
    }

    public boolean isZero() {
        for (byte b : wire) if (b != 0) return false;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RvnHash256 h && Arrays.equals(wire, h.wire);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(wire);
    }

    @Override
    public int compareTo(RvnHash256 other) {
        for (int i = 31; i >= 0; i--) {
            int a = wire[i] & 255, b = other.wire[i] & 255;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    @Override
    public String toString() {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[64];
        for (int i = 0; i < 32; i++) {
            int v = wire[31 - i] & 255;
            out[2 * i] = hex[v >>> 4];
            out[2 * i + 1] = hex[v & 15];
        }
        return new String(out);
    }
}

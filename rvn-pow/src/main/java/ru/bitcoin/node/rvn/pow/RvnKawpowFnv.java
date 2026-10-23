package ru.bitcoin.node.rvn.pow;

/**
 * FNV-1a mixing operation used by ProgPoW/KawPoW, modulo 2^32.
 */
public final class RvnKawpowFnv {
    public static final int PRIME = 0x01000193;
    public static final int OFFSET_BASIS = 0x811c9dc5;

    private RvnKawpowFnv() {
    }

    public static int mix(int a, int b) {
        return (a * PRIME) ^ b;
    }

    /** Ethash dataset parent FNV-1 (xor before multiply). */
    public static int fnv1(int a, int b) { return (a ^ b) * PRIME; }

    public static int reduce(int[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("values");
        int result = OFFSET_BASIS;
        for (int v : values) result = mix(result, v);
        return result;
    }
}

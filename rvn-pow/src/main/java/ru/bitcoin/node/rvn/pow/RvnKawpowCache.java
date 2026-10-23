package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * Ethash-family light cache (64-byte rows) used to derive KawPoW dataset nodes.
 * Immutable snapshot: caller cannot mutate internal cache rows.
 */
public final class RvnKawpowCache {
    public static final int ROW_BYTES = 64;
    public static final int CACHE_ROUNDS = 3;
    private final byte[][] rows;

    private RvnKawpowCache(byte[][] rows) {
        this.rows = rows;
    }

    public static RvnKawpowCache forEpoch(long epoch) {
        long bytes = RvnKawpowDatasetSizes.cacheBytes(epoch);
        if (bytes / ROW_BYTES > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Cache too large");
        return build(RvnKawpowEpochSeed.forEpoch(epoch), (int) (bytes / ROW_BYTES));
    }

    /**
     * Reduced row counts are for deterministic, fast unit tests only.
     * Production callers must use forEpoch().
     */
    static RvnKawpowCache build(byte[] seed, int count) {
        if (seed == null || seed.length != 32) throw new IllegalArgumentException("32-byte seed required");
        if (count < 2) throw new IllegalArgumentException("At least two rows required");
        byte[][] cache = new byte[count][];
        cache[0] = RvnKeccak512.digest(seed);
        for (int i = 1; i < count; i++) cache[i] = RvnKeccak512.digest(cache[i - 1]);
        for (int round = 0; round < CACHE_ROUNDS; round++) {
            for (int i = 0; i < count; i++) {
                int v = (int) (Integer.toUnsignedLong(RvnKawpowWords.readLE(cache[i], 0)) % count);
                byte[] previous = cache[(i - 1 + count) % count];
                byte[] parent = cache[v];
                byte[] xor = new byte[ROW_BYTES];
                for (int j = 0; j < ROW_BYTES; j++) xor[j] = (byte) (previous[j] ^ parent[j]);
                cache[i] = RvnKeccak512.digest(xor);
            }
        }
        return new RvnKawpowCache(cache);
    }

    public int size() {
        return rows.length;
    }

    public byte[] row(int index) {
        return rows[index].clone();
    }

    byte[] rowInternal(int index) {
        return rows[index];
    }
}

package ru.bitcoin.node.rvn.pow;

/**
 * Ethash-family cache and dataset size schedule, used by KawPoW.
 * Values are in bytes. Each epoch reduces to the largest prime row count
 * strictly below the nominal size (64-byte cache rows, 128-byte dataset rows).
 */
public final class RvnKawpowDatasetSizes {
    public static final long CACHE_INIT = 1L << 24;
    public static final long CACHE_GROWTH = 1L << 17;
    public static final long DATASET_INIT = 1L << 30;
    public static final long DATASET_GROWTH = 1L << 23;

    private RvnKawpowDatasetSizes() {
    }

    public static long cacheBytes(long epoch) {
        return sized(epoch, CACHE_INIT, CACHE_GROWTH, 64);
    }

    public static long datasetBytes(long epoch) {
        return sized(epoch, DATASET_INIT, DATASET_GROWTH, 128);
    }

    private static long sized(long epoch, long init, long growth, int rowBytes) {
        if (epoch < 0) throw new IllegalArgumentException("Negative epoch");
        long nominal = Math.addExact(init, Math.multiplyExact(epoch, growth));
        long rows = nominal / rowBytes - 1;
        while (!isPrime(rows)) rows -= 2;
        return Math.multiplyExact(rows, rowBytes);
    }

    static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n % 2 == 0) return n == 2;
        if (n % 3 == 0) return n == 3;
        for (long d = 5; d <= n / d; d += 6)
            if (n % d == 0 || n % (d + 2) == 0) return false;
        return true;
    }
}

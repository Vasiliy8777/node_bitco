package ru.bitcoin.node.mempool;

/**
 * Bitcoin Core v31-style cluster resource limits. maxPoolVirtualBytes is a serialized-size
 * budget, not Bitcoin Core's allocator-specific dynamic-memory accounting.
 */
public record MempoolLimits(int clusterCount, long clusterVirtualBytes, long maxPoolVirtualBytes,
                            long expirySeconds, long incrementalRelaySatPerKvB) {
    public static final MempoolLimits DEFAULT = new MempoolLimits(64, 101_000, 300_000_000,
            336 * 3600L, 100);

    public MempoolLimits {
        if (clusterCount < 1 || clusterVirtualBytes < 1 || maxPoolVirtualBytes < 1
                || expirySeconds < 1 || incrementalRelaySatPerKvB < 0)
            throw new IllegalArgumentException("Invalid mempool limits");
    }
}

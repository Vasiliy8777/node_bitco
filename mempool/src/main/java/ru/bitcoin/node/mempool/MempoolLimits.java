package ru.bitcoin.node.mempool;

/**
 * Bitcoin Core cluster resource limits. The pool budget uses Core's deterministic
 * release x64 allocator estimate; clusterVirtualBytes remains a policy vsize limit.
 */
public record MempoolLimits(int clusterCount, long clusterVirtualBytes, long maxPoolMemoryBytes,
                            long expirySeconds, long incrementalRelaySatPerKvB) {
    public static final MempoolLimits DEFAULT = new MempoolLimits(64, 101_000, 300_000_000,
            336 * 3600L, 100);

    public MempoolLimits {
        if (clusterCount < 1 || clusterVirtualBytes < 1 || maxPoolMemoryBytes < 1
                || expirySeconds < 1 || incrementalRelaySatPerKvB < 0)
            throw new IllegalArgumentException("Invalid mempool limits");
    }
    /** Retained source compatibility for callers; the value is now a memory budget. */
    @Deprecated public long maxPoolVirtualBytes() { return maxPoolMemoryBytes; }
}

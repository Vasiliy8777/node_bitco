package ru.bitcoin.node.mempool;

/** Resource limits are local policy. maxPoolVirtualBytes is a serialized-size budget,
 * not Bitcoin Core's allocator-specific dynamic-memory accounting. */
public record MempoolLimits(int ancestors, int descendants, long familyVirtualBytes,
                            long maxPoolVirtualBytes, long expirySeconds, long incrementalRelaySatPerKvB) {
    public static final MempoolLimits DEFAULT = new MempoolLimits(25, 25, 101_000, 300_000_000,
            336 * 3600L, 100);
    public MempoolLimits {
        if (ancestors < 1 || descendants < 1 || familyVirtualBytes < 1 || maxPoolVirtualBytes < 1
                || expirySeconds < 1 || incrementalRelaySatPerKvB < 0) throw new IllegalArgumentException("Invalid mempool limits");
    }
}

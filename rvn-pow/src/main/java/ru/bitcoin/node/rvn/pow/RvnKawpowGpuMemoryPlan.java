package ru.bitcoin.node.rvn.pow;

/** GPU-worker admission budget, not a CUDA allocation or a mining engine.
 * Dataset access must remain in device memory; disk caching is only useful
 * across launches/epochs, never as a fallback for random accesses while hashing. */
public record RvnKawpowGpuMemoryPlan(long epoch, long dagBytes, long lightCacheBytes,
                                      long reserveBytes, long freeDeviceBytes) {
    public static final long DEFAULT_RESERVE_BYTES = 1L << 30;

    public RvnKawpowGpuMemoryPlan {
        if (epoch < 0 || dagBytes < 1 || lightCacheBytes < 1 || reserveBytes < 0 || freeDeviceBytes < 0)
            throw new IllegalArgumentException("RVN GPU memory plan values");
    }

    public static RvnKawpowGpuMemoryPlan calculate(long height, long totalDeviceBytes,
                                                   long freeDeviceBytes, long reserveBytes) {
        if (height < 0 || height > Integer.MAX_VALUE || totalDeviceBytes < 1
                || freeDeviceBytes < 0 || freeDeviceBytes > totalDeviceBytes || reserveBytes < 0)
            throw new IllegalArgumentException("RVN GPU memory budget");
        long epoch = RvnKawpowEpoch.fromHeight(height);
        return new RvnKawpowGpuMemoryPlan(epoch, RvnKawpowDatasetSizes.datasetBytes(epoch),
                RvnKawpowDatasetSizes.cacheBytes(epoch), reserveBytes, freeDeviceBytes);
    }

    public boolean fits() { return reserveBytes <= freeDeviceBytes && dagBytes <= freeDeviceBytes - reserveBytes; }

    public void requireFits() {
        if (!fits()) throw new IllegalStateException("RVN KawPoW DAG and device reserve do not fit in free VRAM; no RAM/disk spill allowed");
    }
}

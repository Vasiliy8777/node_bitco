package ru.bitcoin.node.rvn.pow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lazy 128-byte KawPoW DAG item reader backed by an Ethash light cache.
 * A bounded access-order LRU avoids materializing a multi-gigabyte DAG.
 * <p>
 * Synchronized cache access prevents corrupted LRU state. A caller always
 * receives a copy, never the internal cached array.
 */
public final class RvnKawpowLightDag implements RvnKawpowDagReader {
    public static final int ITEM_BYTES = 128;
    private final RvnKawpowCache lightCache;
    private final long count;
    private final int maxCachedItems;
    private final Map<Long, byte[]> lru;
    private long hits, misses;

    public RvnKawpowLightDag(RvnKawpowCache lightCache, long datasetBytes,
                             int maxCachedItems) {
        this.lightCache = Objects.requireNonNull(lightCache, "lightCache");
        if (datasetBytes <= 0 || datasetBytes % ITEM_BYTES != 0)
            throw new IllegalArgumentException("DAG size must be positive and 128-byte aligned");
        this.count = datasetBytes / ITEM_BYTES;
        // Current dataset node builder accepts indices up to unsigned 32-bit.
        // Every DAG item requires two consecutive node indices.
        if (count > 0x8000_0000L)
            throw new IllegalArgumentException("DAG exceeds supported node-index range");
        if (maxCachedItems < 0)
            throw new IllegalArgumentException("Negative cache capacity");
        this.maxCachedItems = maxCachedItems;
        this.lru = new LinkedHashMap<>(16, 0.75f, true);
    }

    public static RvnKawpowLightDag forEpoch(long epoch, int maxCachedItems) {
        return new RvnKawpowLightDag(RvnKawpowCache.forEpoch(epoch),
                RvnKawpowDatasetSizes.datasetBytes(epoch), maxCachedItems);
    }

    @Override
    public long itemCount() {
        return count;
    }

    @Override
    public synchronized byte[] item(long index) {
        if (index < 0 || index >= count)
            throw new IndexOutOfBoundsException("DAG item " + index + " outside [0," + count + ")");
        byte[] cached = lru.get(index);
        if (cached != null) {
            hits++;
            return cached.clone();
        }
        byte[] computed = RvnKawpowDatasetItem.dagItem(lightCache, index);
        if (computed.length != ITEM_BYTES)
            throw new IllegalStateException("Invalid DAG item size");
        misses++;
        if (maxCachedItems > 0) {
            lru.put(index, computed.clone());
            if (lru.size() > maxCachedItems)
                lru.remove(lru.keySet().iterator().next());
        }
        return computed.clone();
    }

    public synchronized int cachedItemCount() {
        return lru.size();
    }

    public synchronized long cacheHits() {
        return hits;
    }

    public synchronized long cacheMisses() {
        return misses;
    }

    public synchronized void clearCache() {
        lru.clear();
    }
}

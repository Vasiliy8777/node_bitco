package ru.bitcoin.node.rvn.pow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Thread-safe lazy DAG with bounded LRU and single-flight computation per item.
 * Expensive dataset generation is performed outside the shared monitor, so
 * distinct DAG indexes may be calculated concurrently. Returned bytes are copies.
 */
public final class RvnKawpowLightDag implements RvnKawpowDagReader {
    public static final int ITEM_BYTES = 128;
    private final RvnKawpowCache lightCache;
    private final long count;
    private final int maxCachedItems;
    private final Map<Long, byte[]> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Long, CompletableFuture<byte[]>> inFlight = new java.util.HashMap<>();
    private long hits, misses, sharedWaits, evictions;

    public RvnKawpowLightDag(RvnKawpowCache lightCache, long datasetBytes, int maxCachedItems) {
        this.lightCache = Objects.requireNonNull(lightCache, "lightCache");
        if (datasetBytes <= 0 || datasetBytes % ITEM_BYTES != 0)
            throw new IllegalArgumentException("DAG size must be positive and 128-byte aligned");
        this.count = datasetBytes / ITEM_BYTES;
        if (count > 0x8000_0000L)
            throw new IllegalArgumentException("DAG exceeds supported node-index range");
        if (maxCachedItems < 0) throw new IllegalArgumentException("Negative cache capacity");
        this.maxCachedItems = maxCachedItems;
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
    public byte[] item(long index) {
        if (index < 0 || index >= count)
            throw new IndexOutOfBoundsException("DAG item " + index + " outside [0," + count + ")");
        CompletableFuture<byte[]> future;
        boolean owner;
        synchronized (this) {
            byte[] cached = lru.get(index);
            if (cached != null) {
                hits++;
                return cached.clone();
            }
            future = inFlight.get(index);
            owner = future == null;
            if (owner) {
                future = new CompletableFuture<>();
                inFlight.put(index, future);
            } else sharedWaits++;
        }
        if (!owner) {
            try {
                return future.join().clone();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error err) throw err;
                throw e;
            }
        }
        try {
            byte[] computed = RvnKawpowDatasetItem.dagItem(lightCache, index);
            if (computed == null || computed.length != ITEM_BYTES)
                throw new IllegalStateException("Invalid DAG item size");
            synchronized (this) {
                misses++;
                if (maxCachedItems > 0) {
                    lru.put(index, computed.clone());
                    if (lru.size() > maxCachedItems) {
                        lru.remove(lru.keySet().iterator().next());
                        evictions++;
                    }
                }
                future.complete(computed);
                inFlight.remove(index);
            }
            return computed.clone();
        } catch (Throwable t) {
            synchronized (this) {
                future.completeExceptionally(t);
                inFlight.remove(index);
            }
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error err) throw err;
            throw new IllegalStateException("DAG computation failed", t);
        }
    }

    public synchronized int cachedItemCount() {
        return lru.size();
    }

    public synchronized int inFlightCount() {
        return inFlight.size();
    }

    public synchronized long cacheHits() {
        return hits;
    }

    public synchronized long cacheMisses() {
        return misses;
    }

    public synchronized long sharedWaits() {
        return sharedWaits;
    }

    public synchronized long cacheEvictions() {
        return evictions;
    }

    public synchronized void clearCache() {
        lru.clear();
    }
}

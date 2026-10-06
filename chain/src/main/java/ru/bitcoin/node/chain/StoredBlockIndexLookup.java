package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class StoredBlockIndexLookup implements BlockIndexAncestorLookup {
    private static final int DEFAULT_CACHE_ENTRIES = 131_072;
    private static final int DIAGNOSTIC_SAMPLE_MASK = 127; // one persistent miss out of 128
    private static final StackWalker DIAGNOSTIC_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final AtomicLong persistentMisses = new AtomicLong();
    private final Map<String, LongAdder> persistentMissSamplesByCaller = new ConcurrentHashMap<>();

    private final BlockIndexStore store;
    private final Map<Hash256, BlockIndex> cache;
    private final Map<Hash256, Hash256> skipHashCache;

    public StoredBlockIndexLookup(BlockIndexStore store) {
        this(store, DEFAULT_CACHE_ENTRIES);
    }

    StoredBlockIndexLookup(BlockIndexStore store, int cacheEntries) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (cacheEntries < 0) throw new IllegalArgumentException("cacheEntries must not be negative");
        this.store = store;
        this.cache = cacheEntries == 0 ? null : new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Hash256, BlockIndex> eldest) {
                return size() > cacheEntries;
            }
        };
        this.skipHashCache = cacheEntries == 0 ? null : new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Hash256, Hash256> eldest) {
                return size() > cacheEntries;
            }
        };
    }

    /**
     * Publishes an index that has already passed the chain transition commit.
     * Linear IBD creates the child in memory; retaining it here prevents the next
     * block from immediately reading its parent back from RocksDB.
     */
    public void rememberCommitted(BlockIndex index) {
        if (index == null || cache == null) return;
        synchronized (cache) {
            cache.put(index.hash(), index);
        }
    }

    @Override
    public BlockIndex find(Hash256 hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        if (cache != null) {
            synchronized (cache) {
                BlockIndex cached = cache.get(hash);
                if (cached != null) return cached;
            }
        }
        long currentMiss = persistentMisses.incrementAndGet();
        if ((currentMiss & DIAGNOSTIC_SAMPLE_MASK) == 0L) samplePersistentMissCaller();
        BlockIndex loaded = store.find(hash).map(BlockIndexStorageMapper::fromStored).orElse(null);
        // Do not negative-cache: header sync can add a previously unknown hash.
        if (loaded != null && cache != null) {
            synchronized (cache) {
                cache.put(hash, loaded);
            }
        }
        return loaded;
    }

    @Override
    public BlockIndex ancestor(BlockIndex index, long targetHeight) {
        return ancestor(index, targetHeight, () -> { });
    }

    /** Identity check for persistence optimizations that require committed indexes. */
    public boolean isBackedBy(BlockIndexStore candidateStore) {
        return store == candidateStore;
    }

    @Override
    public BlockIndex ancestor(BlockIndex index, long targetHeight, Runnable checkpoint) {
        java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        checkpoint.run();
        if (index == null) throw new IllegalArgumentException("index must not be null");
        if (targetHeight < 0 || targetHeight > index.height()) {
            throw new IllegalArgumentException("Invalid ancestor height: " + targetHeight);
        }
        if (!(store instanceof RocksDbBlockIndexStore rocks)) {
            return linearAncestor(index, targetHeight, checkpoint);
        }
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            checkpoint.run();
            long heightSkip = RocksDbBlockIndexStore.getSkipHeight(current.height());
            long heightSkipPrev = RocksDbBlockIndexStore.getSkipHeight(current.height() - 1);
            boolean useSkip = heightSkip == targetHeight
                    || (heightSkip > targetHeight && !(heightSkipPrev < heightSkip - 2
                    && heightSkipPrev >= targetHeight));
            // Core can inspect an in-memory pskip pointer cheaply. Here the hash
            // lives in RocksDB: do not fetch it when this step must use pprev.
            Hash256 skipHash = useSkip ? findSkipHash(rocks, current.hash()) : null;
            useSkip = useSkip && skipHash != null;
            Hash256 nextHash = useSkip ? skipHash : current.previousBlockHash();
            BlockIndex next = find(nextHash);
            if (next == null) throw new IllegalStateException("Missing ancestor for block "
                    + current.hash().toDisplayHex() + " at height " + current.height());
            long expected = useSkip ? heightSkip : current.height() - 1;
            if (next.height() != expected) throw new IllegalStateException("Invalid ancestor height: expected "
                    + expected + " but found " + next.height());
            current = next;
        }
        return current;
    }

    /**
     * Lightweight IBD diagnostic. Only one actual BlockIndex-store miss out of 128
     * captures a stack, so attribution does not turn the hot path into a profiler.
     * Values are samples, not estimated call counts.
     */
    public DiagnosticSnapshot diagnosticSnapshot() {
        Map<String, Long> samples = persistentMissSamplesByCaller.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum()));
        return new DiagnosticSnapshot(persistentMisses.get(), samples);
    }

    private void samplePersistentMissCaller() {
        String caller = DIAGNOSTIC_WALKER.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass() != StoredBlockIndexLookup.class)
                .findFirst()
                .map(frame -> frame.getDeclaringClass().getSimpleName() + "." + frame.getMethodName())
                .orElse("unknown"));
        persistentMissSamplesByCaller.computeIfAbsent(caller, ignored -> new LongAdder()).increment();
    }

    public record DiagnosticSnapshot(long persistentMisses, Map<String, Long> samplesByCaller) {
        public DiagnosticSnapshot minus(DiagnosticSnapshot baseline) {
            if (baseline == null) return this;
            Map<String, Long> delta = new java.util.HashMap<>();
            for (Map.Entry<String, Long> entry : samplesByCaller.entrySet()) {
                long value = entry.getValue() - baseline.samplesByCaller.getOrDefault(entry.getKey(), 0L);
                if (value > 0) delta.put(entry.getKey(), value);
            }
            return new DiagnosticSnapshot(Math.max(0L, persistentMisses - baseline.persistentMisses), Map.copyOf(delta));
        }
    }

    private Hash256 findSkipHash(RocksDbBlockIndexStore rocks, Hash256 blockHash) {
        if (skipHashCache != null) {
            synchronized (skipHashCache) {
                Hash256 cached = skipHashCache.get(blockHash);
                if (cached != null) return cached;
            }
        }
        Hash256 loaded = rocks.findSkipHash(blockHash).orElse(null);
        // As with BlockIndex, do not negative-cache: skip data may be populated later.
        if (loaded != null && skipHashCache != null) {
            synchronized (skipHashCache) {
                skipHashCache.put(blockHash, loaded);
            }
        }
        return loaded;
    }

    private BlockIndex linearAncestor(BlockIndex index, long targetHeight, Runnable checkpoint) {
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            checkpoint.run();
            BlockIndex parent = find(current.previousBlockHash());
            if (parent == null) throw new IllegalStateException("Missing ancestor for block "
                    + current.hash().toDisplayHex() + " at height " + current.height());
            current = parent;
        }
        return current;
    }
}

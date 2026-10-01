package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StoredBlockIndexLookup implements BlockIndexAncestorLookup {
    private static final int DEFAULT_CACHE_ENTRIES = 131_072;

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

    @Override
    public BlockIndex find(Hash256 hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        if (cache != null) {
            synchronized (cache) {
                BlockIndex cached = cache.get(hash);
                if (cached != null) return cached;
            }
        }
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
        if (index == null) throw new IllegalArgumentException("index must not be null");
        if (targetHeight < 0 || targetHeight > index.height()) {
            throw new IllegalArgumentException("Invalid ancestor height: " + targetHeight);
        }
        if (!(store instanceof RocksDbBlockIndexStore rocks)) {
            return linearAncestor(index, targetHeight);
        }
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            long heightSkip = RocksDbBlockIndexStore.getSkipHeight(current.height());
            long heightSkipPrev = RocksDbBlockIndexStore.getSkipHeight(current.height() - 1);
            Hash256 skipHash = findSkipHash(rocks, current.hash());
            boolean useSkip = skipHash != null && (heightSkip == targetHeight
                    || (heightSkip > targetHeight && !(heightSkipPrev < heightSkip - 2
                    && heightSkipPrev >= targetHeight)));
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

    private BlockIndex linearAncestor(BlockIndex index, long targetHeight) {
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            BlockIndex parent = find(current.previousBlockHash());
            if (parent == null) throw new IllegalStateException("Missing ancestor for block "
                    + current.hash().toDisplayHex() + " at height " + current.height());
            current = parent;
        }
        return current;
    }
}

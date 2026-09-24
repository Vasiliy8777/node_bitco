package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockFailureStore;

import java.util.*;

/** Resolves immutable block-index ancestry against committed failure state. */
public final class BlockFailureResolver {
    static final int MAX_CACHE_ENTRIES = 65_536;
    private final BlockIndexLookup lookup;
    private final BlockFailureStore failureStore;
    private final LinkedHashMap<Hash256, Boolean> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash256, Boolean> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private long cachedRevision = -1;
    private Hash256 cachedAdditionalFailure;

    public BlockFailureResolver(BlockIndexLookup lookup, BlockFailureStore failureStore) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.failureStore = Objects.requireNonNull(failureStore, "failureStore");
    }

    public boolean isFailed(BlockIndex index) {
        return isFailed(index, null);
    }

    public synchronized boolean isFailed(BlockIndex index, Hash256 additionallyFailed) {
        Objects.requireNonNull(index, "index");
        while (true) {
            long revision = failureStore.revision();
            if (revision < 0 || revision != cachedRevision
                    || !Objects.equals(additionallyFailed, cachedAdditionalFailure)) {
                cache.clear();
                cachedRevision = revision;
                cachedAdditionalFailure = additionallyFailed;
            }
            boolean failed = resolve(index, additionallyFailed);
            // A concurrent committed invalidation must not leave a stale result in the cache.
            if (revision < 0 || failureStore.revision() == revision) return failed;
            cache.clear();
        }
    }

    private boolean resolve(BlockIndex index, Hash256 additionallyFailed) {
        BlockIndex current = index;
        Set<Hash256> visited = new HashSet<>();
        List<Hash256> path = new ArrayList<>();
        boolean failed;
        while (true) {
            if (!visited.add(current.hash())) {
                throw new IllegalStateException("Cycle detected in block-index ancestry at "
                        + current.hash().toDisplayHex());
            }
            Boolean known = cache.get(current.hash());
            if (known != null) {
                failed = known;
                break;
            }
            path.add(current.hash());
            if (current.hash().equals(additionallyFailed) || failureStore.isFailed(current.hash())) {
                failed = true;
                break;
            }
            if (current.height() == 0) {
                failed = false;
                break;
            }
            BlockIndex parent = lookup.find(current.previousBlockHash());
            if (parent == null) {
                throw new IllegalStateException("Missing BlockIndex ancestor while resolving failure state: "
                        + current.previousBlockHash().toDisplayHex());
            }
            current = parent;
        }
        // Cache from root towards tip so the most useful recent entries survive eviction.
        for (int i = path.size() - 1; i >= 0; i--) cache.put(path.get(i), failed);
        return failed;
    }
}
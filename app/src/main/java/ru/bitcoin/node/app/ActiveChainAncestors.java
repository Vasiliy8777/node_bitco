package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexAncestorLookup;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.common.types.Hash256;
import java.util.*;

/**
 * Bounded active-chain ancestry cache; caller holds the chain monitor.
 *
 * <p>A normal tip extension does not invalidate historical active-chain entries: every
 * ancestor of the old tip is necessarily an ancestor of its descendant. Reorganizations
 * and rollbacks still invalidate the cache. This matters during IBD where the active tip
 * advances continuously while peers repeatedly ask about historical locator heights.</p>
 */
final class ActiveChainAncestors {
    private static final int ASCENDING_PREFETCH_ENTRIES = 4_096;
    private final int capacity;

    /*
     * Two different access patterns must not evict each other.  During IBD the tip
     * advances one block at a time and callers very frequently ask about recent
     * active-chain heights.  Peer locators, RPC/index lookups and reorg probes also
     * touch sparse historical heights.  A single LRU let those sparse reads punch
     * holes in the recent 65k active-chain window, turning a later near-tip lookup
     * into a fresh skip-pointer walk through RocksDB.
     *
     * hotHeights is therefore a strict rolling height window: historical touches do
     * not affect its eviction order. sparseHeights keeps the old LRU behaviour for
     * genuinely historical queries.
     */
    private final NavigableMap<Long, BlockIndex> hotHeights = new TreeMap<>();
    private final NavigableMap<Long, BlockIndex> sparseHeights = new TreeMap<>();
    private final LinkedHashMap<Long, Boolean> sparseUsage = new LinkedHashMap<>(256, .75f, true);
    private Hash256 tipHash;
    private long tipHeight = -1L;
    private long lastSparseMissHeight = -2L;

    ActiveChainAncestors() { this(65_536); }

    ActiveChainAncestors(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    /** Records a successfully committed linear-Ibd tip without any storage lookup. */
    void rememberCommitted(BlockIndex index) {
        Objects.requireNonNull(index, "index");
        if (tipHash != null && index.height() == tipHeight + 1L
                && index.previousBlockHash().equals(tipHash)) {
            tipHash = index.hash();
            tipHeight = index.height();
            rememberHot(index);
            return;
        }
        if (tipHash == null || index.hash().equals(tipHash)) {
            tipHash = index.hash();
            tipHeight = index.height();
            rememberHot(index);
            return;
        }
        // A non-linear transition is a reorg/rollback boundary. Do not retain ancestry
        // from the old branch; the next lookup will rebuild only what is actually active.
        hotHeights.clear();
        sparseHeights.clear();
        sparseUsage.clear();
        lastSparseMissHeight = -2L;
        tipHash = index.hash();
        tipHeight = index.height();
        rememberHot(index);
    }

    BlockIndex at(BlockIndex tip, long height, BlockIndexLookup lookup) {
        Objects.requireNonNull(tip, "tip");
        Objects.requireNonNull(lookup, "lookup");
        if (height < 0 || height > tip.height()) return null;

        reconcileTip(tip, lookup);

        BlockIndex exact = hotHeights.get(height);
        if (exact != null) return exact;

        exact = sparseHeights.get(height);
        if (exact != null) {
            touchSparse(height);
            return exact;
        }

        Map.Entry<Long, BlockIndex> hotCeiling = hotHeights.ceilingEntry(height);
        Map.Entry<Long, BlockIndex> sparseCeiling = sparseHeights.ceilingEntry(height);
        BlockIndex cursor = nearestCeiling(hotCeiling, sparseCeiling, tip);

        // Production lookup can prove ancestry with Core-style skip pointers.  On a cache
        // miss, use that directly instead of walking every parent between cursor and the
        // requested height.  The old linear walk both caused O(distance) RocksDB reads and
        // polluted this bounded cache with thousands of one-shot intermediate heights,
        // evicting the useful locator/range entries that IBD repeatedly asks for.
        if (lookup instanceof BlockIndexAncestorLookup ancestorLookup) {
            // Historical range consumers (headers/filter/RPC scans) normally ask for
            // monotonically increasing heights. Resolving each height independently with
            // skip pointers is branch-safe but still performs a fresh root-to-height walk.
            // Detect the second consecutive miss and materialize a bounded dense slice once;
            // the following ~4k ascending requests then become zero-read cache hits. Random
            // one-shot historical queries keep the cheap single skip-ancestor lookup.
            if (height == lastSparseMissHeight + 1L) {
                BlockIndex ancestor = prefetchAscendingSlice(ancestorLookup, cursor, height);
                lastSparseMissHeight = height;
                return ancestor;
            }

            BlockIndex ancestor = ancestorLookup.ancestor(cursor, height);
            if (ancestor == null || ancestor.height() != height)
                throw new IllegalStateException("Missing active ancestor at height " + height);
            rememberSparse(ancestor);
            lastSparseMissHeight = height;
            return ancestor;
        }

        // Generic lookups used by callers/tests without skip ancestry retain the original
        // behavior.  Remembering the traversed path is useful for repeated nearby queries.
        rememberSparse(cursor);
        while (cursor.height() > height) {
            BlockIndex parent = Objects.requireNonNull(
                    lookup.find(cursor.previousBlockHash()),
                    "Missing active ancestor"
            );
            if (parent.height() != cursor.height() - 1)
                throw new IllegalStateException("Invalid active ancestor height");
            cursor = parent;
            rememberSparse(cursor);
        }
        return cursor;
    }

    private BlockIndex prefetchAscendingSlice(
            BlockIndexAncestorLookup lookup,
            BlockIndex cursor,
            long requestedHeight
    ) {
        long ceilingHeight = Math.min(
                cursor.height(),
                requestedHeight + Math.min(Math.max(0L, (long) capacity - 2L), ASCENDING_PREFETCH_ENTRIES - 1L)
        );
        BlockIndex current = lookup.ancestor(cursor, ceilingHeight);
        if (current == null || current.height() != ceilingHeight)
            throw new IllegalStateException("Missing active ancestor at height " + ceilingHeight);

        rememberSparse(current);
        while (current.height() > requestedHeight) {
            BlockIndex parent = Objects.requireNonNull(
                    lookup.find(current.previousBlockHash()),
                    "Missing active ancestor"
            );
            if (parent.height() != current.height() - 1L)
                throw new IllegalStateException("Invalid active ancestor height");
            current = parent;
            rememberSparse(current);
        }
        return current;
    }

    private void reconcileTip(BlockIndex tip, BlockIndexLookup lookup) {
        if (tip.hash().equals(tipHash)) return;

        boolean extendsPreviousTip = false;
        if (tipHash != null && tip.height() > tipHeight) {
            if (tip.height() == tipHeight + 1L && tip.previousBlockHash().equals(tipHash)) {
                extendsPreviousTip = true;
            } else if (lookup instanceof BlockIndexAncestorLookup ancestorLookup) {
                try {
                    BlockIndex previousTip = ancestorLookup.ancestor(tip, tipHeight);
                    extendsPreviousTip = previousTip != null && previousTip.hash().equals(tipHash);
                } catch (IllegalStateException ignored) {
                    // Fail safe: if ancestry cannot be proven, discard cached active-chain data.
                }
            }
        }

        if (!extendsPreviousTip) {
            hotHeights.clear();
            sparseHeights.clear();
            sparseUsage.clear();
            lastSparseMissHeight = -2L;
        }
        tipHash = tip.hash();
        tipHeight = tip.height();
        rememberHot(tip);
    }

    private static BlockIndex nearestCeiling(
            Map.Entry<Long, BlockIndex> hot,
            Map.Entry<Long, BlockIndex> sparse,
            BlockIndex tip
    ) {
        if (hot == null) return sparse == null ? tip : sparse.getValue();
        if (sparse == null) return hot.getValue();
        return hot.getKey() <= sparse.getKey() ? hot.getValue() : sparse.getValue();
    }

    private void rememberHot(BlockIndex index) {
        hotHeights.put(index.height(), index);
        while (hotHeights.size() > capacity) hotHeights.pollFirstEntry();
    }

    private void touchSparse(long height) {
        sparseUsage.put(height, Boolean.TRUE);
    }

    private void rememberSparse(BlockIndex index) {
        // Recent active-chain entries already live in the non-pollutable hot window.
        // Avoid duplicating them in the historical LRU.
        if (hotHeights.containsKey(index.height())) return;
        sparseHeights.put(index.height(), index);
        sparseUsage.put(index.height(), Boolean.TRUE);
        if (sparseUsage.size() > capacity) {
            Long eldest = sparseUsage.keySet().iterator().next();
            sparseUsage.remove(eldest);
            sparseHeights.remove(eldest);
        }
    }
}

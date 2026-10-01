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
    private final int capacity;
    private final NavigableMap<Long, BlockIndex> heights = new TreeMap<>();
    private final LinkedHashMap<Long, Boolean> usage = new LinkedHashMap<>(256, .75f, true);
    private Hash256 tipHash;
    private long tipHeight = -1L;

    ActiveChainAncestors() { this(65_536); }

    ActiveChainAncestors(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    BlockIndex at(BlockIndex tip, long height, BlockIndexLookup lookup) {
        Objects.requireNonNull(tip, "tip");
        Objects.requireNonNull(lookup, "lookup");
        if (height < 0 || height > tip.height()) return null;

        reconcileTip(tip, lookup);

        var exact = heights.get(height);
        if (exact != null) {
            touch(height);
            return exact;
        }

        var cached = heights.ceilingEntry(height);
        BlockIndex cursor = cached == null ? tip : cached.getValue();
        remember(cursor);
        while (cursor.height() > height) {
            BlockIndex parent = Objects.requireNonNull(
                    lookup.find(cursor.previousBlockHash()),
                    "Missing active ancestor"
            );
            if (parent.height() != cursor.height() - 1)
                throw new IllegalStateException("Invalid active ancestor height");
            cursor = parent;
            remember(cursor);
        }
        return cursor;
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
            heights.clear();
            usage.clear();
        }
        tipHash = tip.hash();
        tipHeight = tip.height();
        remember(tip);
    }

    private void touch(long height) {
        usage.put(height, Boolean.TRUE);
    }

    private void remember(BlockIndex index) {
        heights.put(index.height(), index);
        usage.put(index.height(), Boolean.TRUE);
        if (usage.size() > capacity) {
            Long eldest = usage.keySet().iterator().next();
            usage.remove(eldest);
            heights.remove(eldest);
        }
    }
}

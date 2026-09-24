package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexLookup;
import ru.bitcoin.node.common.types.Hash256;
import java.util.*;

/** Bounded ancestry cache; caller holds the chain monitor. Invalidated on every tip change. */
final class ActiveChainAncestors {
    private final int capacity;
    private final NavigableMap<Long, BlockIndex> heights = new TreeMap<>();
    private final LinkedHashMap<Long, Boolean> usage = new LinkedHashMap<>(256, .75f, true);
    private Hash256 tipHash;
    ActiveChainAncestors() { this(65_536); }
    ActiveChainAncestors(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    BlockIndex at(BlockIndex tip, long height, BlockIndexLookup lookup) {
        if (height < 0 || height > tip.height()) return null;
        if (!tip.hash().equals(tipHash)) {
            heights.clear(); usage.clear(); tipHash = tip.hash();
        }
        var cached = heights.ceilingEntry(height);
        BlockIndex cursor = cached == null ? tip : cached.getValue();
        remember(cursor);
        while (cursor.height() > height) {
            BlockIndex parent = Objects.requireNonNull(lookup.find(cursor.previousBlockHash()), "Missing active ancestor");
            if (parent.height() != cursor.height() - 1) throw new IllegalStateException("Invalid active ancestor height");
            cursor = parent;
            remember(cursor);
        }
        return cursor;
    }
    private void remember(BlockIndex index) {
        heights.put(index.height(), index);
        usage.put(index.height(), Boolean.TRUE);
        if (usage.size() > capacity) {
            Long eldest = usage.keySet().iterator().next();
            usage.remove(eldest); heights.remove(eldest);
        }
    }
}
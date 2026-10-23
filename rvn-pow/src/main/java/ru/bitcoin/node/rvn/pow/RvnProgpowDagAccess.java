package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * 256-byte DAG fetches with checked little-endian 32-bit word access.
 */
public final class RvnProgpowDagAccess {
    private final RvnKawpowDagReader dag;

    public RvnProgpowDagAccess(RvnKawpowDagReader dag) {
        this.dag = Objects.requireNonNull(dag, "dag");
        if (dag.itemCount() <= 0) throw new IllegalArgumentException("empty DAG");
    }

    public long normalizedIndex(int unsignedIndex) {
        return Integer.toUnsignedLong(unsignedIndex) % dag.itemCount();
    }

    public int[] words(int unsignedIndex) {
        byte[] item = dag.item(normalizedIndex(unsignedIndex));
        if (item == null || item.length != 256) throw new IllegalStateException("invalid DAG item");
        return RvnKawpowWords.decode(item);
    }

    public int word(int unsignedIndex, int wordIndex) {
        if (wordIndex < 0 || wordIndex >= 64) throw new IndexOutOfBoundsException("DAG word");
        return words(unsignedIndex)[wordIndex];
    }
}

package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Maps unsigned 32-bit ProgPoW DAG item indexes to a bounded light DAG.
 * This class only performs indexing and fetches; it does not claim to
 * implement the ProgPoW program, lane shuffle, or consensus mix.
 */
public final class RvnKawpowDagLookup {
    private final RvnKawpowDagReader dag;

    public RvnKawpowDagLookup(RvnKawpowDagReader dag) {
        this.dag = Objects.requireNonNull(dag, "dag");
        if (dag.itemCount() <= 0)
            throw new IllegalArgumentException("Empty DAG");
    }

    public long normalizeUnsigned(int index) {
        return Integer.toUnsignedLong(index) % dag.itemCount();
    }

    public byte[] itemUnsigned(int index) {
        byte[] bytes = dag.item(normalizeUnsigned(index));
        if (bytes == null || bytes.length != RvnKawpowLightDag.ITEM_BYTES)
            throw new IllegalStateException("DAG provider returned invalid item");
        return bytes.clone();
    }

    public int wordUnsigned(int index, int word) {
        if (word < 0 || word >= 32)
            throw new IndexOutOfBoundsException("DAG word index");
        return RvnKawpowWords.readLE(itemUnsigned(index), word * 4);
    }
}

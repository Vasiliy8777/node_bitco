package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Fixed-size, defensive DAG page for contiguous reads.
 */
public final class RvnKawpowDagPage {
    private final long firstIndex;
    private final byte[][] items;

    public RvnKawpowDagPage(RvnKawpowDagReader reader, long firstIndex, int count) {
        Objects.requireNonNull(reader, "reader");
        if (count < 1 || count > 4096 || firstIndex < 0 || firstIndex > reader.itemCount()
                || count > reader.itemCount() - firstIndex)
            throw new IllegalArgumentException("Invalid DAG page range");
        this.firstIndex = firstIndex;
        this.items = new byte[count][];
        for (int i = 0; i < count; i++) {
            byte[] value = reader.item(firstIndex + i);
            if (value == null || value.length != 128) throw new IllegalStateException("Invalid DAG item");
            items[i] = value.clone();
        }
    }

    public long firstIndex() {
        return firstIndex;
    }

    public int size() {
        return items.length;
    }

    public byte[] itemAt(int offset) {
        return items[offset].clone();
    }

    public byte[] wordBytes(int offset, int word) {
        if (word < 0 || word >= 32) throw new IndexOutOfBoundsException("word");
        return java.util.Arrays.copyOfRange(items[offset], word * 4, word * 4 + 4);
    }

    public int wordLE(int offset, int word) {
        if (word < 0 || word >= 32) throw new IndexOutOfBoundsException("word");
        return RvnKawpowWords.readLE(items[offset], word * 4);
    }
}

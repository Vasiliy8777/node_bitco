package ru.bitcoin.node.rvn.pow;

/**
 * 16KiB KawPoW L1 cache, generated from first 64 256-byte DAG items.
 */
public final class RvnProgpowL1Cache {
    public static final int WORDS = 4096;
    private final int[] words;

    private RvnProgpowL1Cache(int[] words) {
        this.words = words;
    }

    public static RvnProgpowL1Cache fromDag(RvnKawpowDagReader dag) {
        if (dag == null || dag.itemCount() < 64) throw new IllegalArgumentException("At least 64 DAG items required");
        int[] data = new int[WORDS];
        for (int i = 0; i < 64; i++) {
            byte[] item = dag.item(i);
            if (item == null || item.length != 256) throw new IllegalStateException("Invalid DAG item");
            for (int j = 0; j < 64; j++) data[i * 64 + j] = RvnKawpowWords.readLE(item, j * 4);
        }
        return new RvnProgpowL1Cache(data);
    }

    public int word(int index) {
        if (index < 0 || index >= WORDS) throw new IndexOutOfBoundsException();
        return words[index];
    }

    public int size() {
        return WORDS;
    }
}

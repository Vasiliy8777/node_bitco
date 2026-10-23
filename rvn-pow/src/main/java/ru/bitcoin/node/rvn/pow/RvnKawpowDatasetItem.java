package ru.bitcoin.node.rvn.pow;

/**
 * Ethash light-client 64-byte dataset node calculation, 512 parent accesses.
 * KawPoW consumes 256-byte ProgPoW DAG items assembled from four consecutive nodes.
 */
public final class RvnKawpowDatasetItem {
    public static final int PARENTS = 512;

    private RvnKawpowDatasetItem() {
    }

    public static byte[] node(RvnKawpowCache cache, long nodeIndex) {
        if (cache == null || nodeIndex < 0 || nodeIndex > 0xffff_ffffL)
            throw new IllegalArgumentException("Invalid cache or node index");
        int count = cache.size();
        byte[] mix = cache.row((int) (nodeIndex % count));
        RvnKawpowWords.writeLE(RvnKawpowWords.readLE(mix, 0) ^ (int) nodeIndex, mix, 0);
        mix = RvnKeccak512.digest(mix);
        int[] words = RvnKawpowWords.decode(mix);
        for (int parent = 0; parent < PARENTS; parent++) {
            int p = RvnKawpowFnv.fnv1((int) nodeIndex ^ parent, words[parent % 16]);
            int cacheIndex = (int) (Integer.toUnsignedLong(p) % count);
            byte[] source = cache.rowInternal(cacheIndex);
            for (int w = 0; w < 16; w++)
                words[w] = RvnKawpowFnv.fnv1(words[w], RvnKawpowWords.readLE(source, w * 4));
        }
        return RvnKeccak512.digest(RvnKawpowWords.encode(words));
    }

    public static byte[] dagItem(RvnKawpowCache cache, long itemIndex) {
        if (itemIndex < 0 || itemIndex > 0x3fff_ffffL)
            throw new IllegalArgumentException("Invalid DAG item index");
        byte[] result = new byte[256];
        for (int i = 0; i < 4; i++)
            System.arraycopy(node(cache, itemIndex * 4 + i), 0, result, i * 64, 64);
        return result;
    }
}

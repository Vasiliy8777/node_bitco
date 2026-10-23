package ru.bitcoin.node.rvn.pow;

/**
 * Ethash light-client 64-byte dataset node calculation, 256 parent accesses.
 * KawPoW consumes 128-byte DAG items assembled from two consecutive nodes.
 */
public final class RvnKawpowDatasetItem {
    public static final int PARENTS = 256;

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
            int p = RvnKawpowFnv.mix((int) nodeIndex ^ parent, words[parent % 16]);
            int cacheIndex = (int) (Integer.toUnsignedLong(p) % count);
            byte[] source = cache.rowInternal(cacheIndex);
            for (int w = 0; w < 16; w++)
                words[w] = RvnKawpowFnv.mix(words[w], RvnKawpowWords.readLE(source, w * 4));
        }
        return RvnKeccak512.digest(RvnKawpowWords.encode(words));
    }

    public static byte[] dagItem(RvnKawpowCache cache, long itemIndex) {
        if (itemIndex < 0 || itemIndex > 0x7fff_ffffL)
            throw new IllegalArgumentException("Invalid DAG item index");
        byte[] result = new byte[128];
        System.arraycopy(node(cache, itemIndex * 2), 0, result, 0, 64);
        System.arraycopy(node(cache, itemIndex * 2 + 1), 0, result, 64, 64);
        return result;
    }
}

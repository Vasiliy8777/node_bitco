package ru.bitcoin.node.rvn.pow;

/**
 * Reduce a ProgPoW mix (16 lanes x 32 words) to 8 little-endian FNV words.
 */
public final class RvnKawpowMixReducer {
    public static final int LANES = 16;
    public static final int WORDS_PER_LANE = 32;

    private RvnKawpowMixReducer() {
    }

    public static byte[] reduce(int[][] mix) {
        if (mix == null || mix.length != LANES) throw new IllegalArgumentException("16 lanes required");
        int[] result = new int[8];
        java.util.Arrays.fill(result, RvnKawpowFnv.OFFSET_BASIS);
        for (int lane = 0; lane < LANES; lane++) {
            if (mix[lane] == null || mix[lane].length != WORDS_PER_LANE)
                throw new IllegalArgumentException("32 words required in each lane");
            int hash = RvnKawpowFnv.OFFSET_BASIS;
            for (int word : mix[lane]) hash = RvnKawpowFnv.mix(hash, word);
            result[lane % 8] = RvnKawpowFnv.mix(result[lane % 8], hash);
        }
        return RvnKawpowWords.encode(result);
    }
}

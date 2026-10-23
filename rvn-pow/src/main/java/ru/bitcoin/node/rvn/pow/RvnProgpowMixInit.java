package ru.bitcoin.node.rvn.pow;

/**
 * Initialize 16 lanes x 32 words from the two-word Keccak-f800 seed.
 */
public final class RvnProgpowMixInit {
    private RvnProgpowMixInit() {
    }

    public static RvnProgpowLaneState initialize(int seedLo, int seedHi) {
        int z = RvnKawpowFnv.mix(RvnKawpowFnv.OFFSET_BASIS, seedLo);
        int w = RvnKawpowFnv.mix(z, seedHi);
        int[][] lanes = new int[16][32];
        for (int lane = 0; lane < 16; lane++) {
            int jsr = RvnKawpowFnv.mix(w, lane);
            int jcong = RvnKawpowFnv.mix(jsr, lane);
            var rng = new RvnProgpowRandom(z, w, jsr, jcong);
            for (int i = 0; i < 32; i++) lanes[lane][i] = rng.nextInt();
        }
        return new RvnProgpowLaneState(lanes);
    }
}

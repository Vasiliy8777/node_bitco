package ru.bitcoin.node.rvn.pow;

/**
 * KawPoW 0.9.4 round and 64-round mix computation.
 */
public final class RvnProgpowRoundEngine {
    public static final int ROUNDS = 64, CACHE_ACCESSES = 11, MATH_OPERATIONS = 18;
    private final RvnKawpowDagReader dag;
    private final RvnProgpowL1Cache l1;

    public RvnProgpowRoundEngine(RvnKawpowDagReader dag, RvnProgpowL1Cache l1) {
        if (dag == null || dag.itemCount() <= 0 || l1 == null) throw new IllegalArgumentException("dag/l1");
        this.dag = dag;
        this.l1 = l1;
    }

    public byte[] calculate(long height, int seedLo, int seedHi) {
        if (height < 0) throw new IllegalArgumentException("height");
        RvnProgpowLaneState state = RvnProgpowMixInit.initialize(seedLo, seedHi);
        for (int round = 0; round < ROUNDS; round++) applyRound(state, height, round);
        return state.reducedMix();
    }

    public void applyRound(RvnProgpowLaneState mix, long height, int round) {
        if (mix == null || height < 0 || round < 0 || round >= ROUNDS) throw new IllegalArgumentException();
        RvnProgpowMixRng rng = new RvnProgpowMixRng(height);
        long index = Integer.toUnsignedLong(mix.get(round % 16, 0)) % dag.itemCount();
        byte[] item = dag.item(index);
        if (item == null || item.length != 256) throw new IllegalStateException("DAG item size");
        for (int i = 0; i < MATH_OPERATIONS; i++) {
            if (i < CACHE_ACCESSES) {
                int src = rng.nextSrc(), dst = rng.nextDst(), selector = rng.nextInt();
                for (int lane = 0; lane < 16; lane++) {
                    int offset = Integer.remainderUnsigned(mix.get(lane, src), RvnProgpowL1Cache.WORDS);
                    mix.merge(lane, dst, l1.word(offset), selector);
                }
            }
            int srcRnd = Integer.remainderUnsigned(rng.nextInt(), 32 * 31);
            int src1 = srcRnd % 32, src2 = srcRnd / 32;
            if (src2 >= src1) src2++;
            int mathSel = rng.nextInt(), dst = rng.nextDst(), mergeSel = rng.nextInt();
            for (int lane = 0; lane < 16; lane++) {
                int v = RvnProgpowMath.apply(mix.get(lane, src1), mix.get(lane, src2), mathSel);
                mix.merge(lane, dst, v, mergeSel);
            }
        }
        int[] dst = new int[4], sel = new int[4];
        for (int i = 0; i < 4; i++) {
            dst[i] = i == 0 ? 0 : rng.nextDst();
            sel[i] = rng.nextInt();
        }
        for (int lane = 0; lane < 16; lane++) {
            int offset = ((lane ^ round) % 16) * 4;
            for (int i = 0; i < 4; i++) {
                int word = RvnKawpowWords.readLE(item, (offset + i) * 4);
                mix.merge(lane, dst[i], word, sel[i]);
            }
        }
    }
}

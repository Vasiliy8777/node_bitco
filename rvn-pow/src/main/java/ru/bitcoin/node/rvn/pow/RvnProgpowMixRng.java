package ru.bitcoin.node.rvn.pow;

/**
 * KawPoW mix RNG: height/3 period seed and interleaved Fisher-Yates permutations.
 */
public final class RvnProgpowMixRng {
    public static final int PERIOD_LENGTH = 3;
    private final RvnProgpowRandom random;
    private final int[] dst = new int[32], src = new int[32];
    private int di, si;

    public RvnProgpowMixRng(long height) {
        if (height < 0) throw new IllegalArgumentException("height");
        long period = height / PERIOD_LENGTH;
        int lo = (int) period, hi = (int) (period >>> 32);
        int z = RvnKawpowFnv.mix(RvnKawpowFnv.OFFSET_BASIS, lo);
        int w = RvnKawpowFnv.mix(z, hi);
        int jsr = RvnKawpowFnv.mix(w, lo);
        int jcong = RvnKawpowFnv.mix(jsr, hi);
        random = new RvnProgpowRandom(z, w, jsr, jcong);
        for (int i = 0; i < 32; i++) {
            dst[i] = i;
            src[i] = i;
        }
        for (int i = 32; i > 1; i--) {
            int a = Integer.remainderUnsigned(random.nextInt(), i);
            int t = dst[i - 1];
            dst[i - 1] = dst[a];
            dst[a] = t;
            int b = Integer.remainderUnsigned(random.nextInt(), i);
            t = src[i - 1];
            src[i - 1] = src[b];
            src[b] = t;
        }
    }

    public int nextDst() {
        return dst[di++ % 32];
    }

    public int nextSrc() {
        return src[si++ % 32];
    }

    public int nextInt() {
        return random.nextInt();
    }
}

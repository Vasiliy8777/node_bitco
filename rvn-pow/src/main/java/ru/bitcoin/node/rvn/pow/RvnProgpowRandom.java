package ru.bitcoin.node.rvn.pow;

/**
 * KISS99 generator with explicit unsigned 32-bit overflow semantics.
 */
public final class RvnProgpowRandom {
    private int z, w, jsr, jcong;

    public RvnProgpowRandom(int z, int w, int jsr, int jcong) {
        this.z = z;
        this.w = w;
        this.jsr = jsr;
        this.jcong = jcong;
    }

    public int nextInt() {
        z = 36969 * (z & 65535) + (z >>> 16);
        w = 18000 * (w & 65535) + (w >>> 16);
        int mwc = (z << 16) + w;
        jsr ^= jsr << 17;
        jsr ^= jsr >>> 13;
        jsr ^= jsr << 5;
        jcong = 69069 * jcong + 1234567;
        return (mwc ^ jcong) + jsr;
    }

    public int[] snapshot() {
        return new int[]{z, w, jsr, jcong};
    }
}

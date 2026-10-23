package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/** KawPoW seed and final sponge, matching Core's progpow.cpp hash(). */
public final class RvnKawpowHash {
    private static final int[] DOMAIN = {
            0x72, 0x41, 0x56, 0x45, 0x4e, 0x43, 0x4f, 0x49,
            0x4e, 0x4b, 0x41, 0x57, 0x50, 0x4f, 0x57
    };

    private RvnKawpowHash() {}

    public static int[] seed(byte[] headerHash, long nonce) {
        if (headerHash == null || headerHash.length != 32)
            throw new IllegalArgumentException("32-byte header hash required");
        int[] state = new int[25];
        System.arraycopy(RvnKawpowWords.decode(headerHash), 0, state, 0, 8);
        state[8] = (int) nonce;
        state[9] = (int) (nonce >>> 32);
        System.arraycopy(DOMAIN, 0, state, 10, 15);
        return Arrays.copyOf(RvnKeccakF800.permute(state), 8);
    }

    /** Returns the native Ethash digest bytes; these are big-endian for target comparison. */
    public static byte[] finish(int[] seed, byte[] mix) {
        if (seed == null || seed.length != 8 || mix == null || mix.length != 32)
            throw new IllegalArgumentException("8 seed words and 32 mix bytes required");
        int[] state = new int[25];
        System.arraycopy(seed, 0, state, 0, 8);
        System.arraycopy(RvnKawpowWords.decode(mix), 0, state, 8, 8);
        System.arraycopy(DOMAIN, 0, state, 16, 9);
        return RvnKawpowWords.encode(Arrays.copyOf(RvnKeccakF800.permute(state), 8));
    }
}

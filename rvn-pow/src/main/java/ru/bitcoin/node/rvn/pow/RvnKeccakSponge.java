package ru.bitcoin.node.rvn.pow;

/**
 * Legacy Keccak sponge: domain 0x01, NOT FIPS SHA3 domain 0x06.
 */
public final class RvnKeccakSponge {
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL,
            0x8000000080008000L, 0x000000000000808bL, 0x0000000080000001L,
            0x8000000080008081L, 0x8000000000008009L, 0x000000000000008aL,
            0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L,
            0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
            0x000000000000800aL, 0x800000008000000aL, 0x8000000080008081L,
            0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };
    private static final int[] ROT = {
            0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39,
            41, 45, 15, 21, 8, 18, 2, 61, 56, 14
    };

    private RvnKeccakSponge() {
    }

    public static byte[] digest256(byte[] input) {
        return digest(input, 136, 32);
    }

    public static byte[] digest512(byte[] input) {
        return digest(input, 72, 64);
    }

    private static byte[] digest(byte[] input, int rate, int outputSize) {
        if (input == null) throw new IllegalArgumentException("input");
        long[] state = new long[25];
        int pos = 0;
        while (input.length - pos >= rate) {
            absorb(state, input, pos, rate);
            permute(state);
            pos += rate;
        }
        byte[] last = new byte[rate];
        int remaining = input.length - pos;
        System.arraycopy(input, pos, last, 0, remaining);
        last[remaining] ^= 1;
        last[rate - 1] ^= (byte) 0x80;
        absorb(state, last, 0, rate);
        permute(state);
        byte[] result = new byte[outputSize];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
        return result;
    }

    private static void absorb(long[] state, byte[] input, int pos, int length) {
        for (int i = 0; i < length; i++) state[i / 8] ^= (input[pos + i] & 255L) << (8 * (i % 8));
    }

    private static void permute(long[] a) {
        long[] b = new long[25], c = new long[5], d = new long[5];
        for (long rc : RC) {
            for (int x = 0; x < 5; x++) c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            for (int x = 0; x < 5; x++) d[x] = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
            for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) a[x + 5 * y] ^= d[x];
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 5; x++) {
                    int i = x + 5 * y;
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[i], ROT[i]);
                }
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 5; x++)
                    a[x + 5 * y] = b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y]);
            a[0] ^= rc;
        }
    }
}

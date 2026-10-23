package ru.bitcoin.node.rvn.pow;

import java.util.Objects;

/**
 * Keccak-f[800] permutation: 25 lanes of 32 bits, 22 rounds.
 * This is a permutation, not a Keccak-256 digest or a complete KawPoW engine.
 */
public final class RvnKeccakF800 {
    private static final int[] RC = {
            0x00000001, 0x00008082, 0x0000808a, 0x80008000, 0x0000808b,
            0x80000001, 0x80008081, 0x00008009, 0x0000008a, 0x00000088,
            0x80008009, 0x8000000a, 0x8000808b, 0x0000008b, 0x00008089,
            0x00008003, 0x00008002, 0x00000080, 0x0000800a, 0x8000000a,
            0x80008081, 0x00008080
    };
    private static final int[] R = {
            0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39,
            41, 45, 15, 21, 8, 18, 2, 61, 56, 14
    };

    private RvnKeccakF800() {
    }

    public static int[] permute(int[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length != 25) throw new IllegalArgumentException("25 32-bit lanes required");
        int[] a = input.clone();
        int[] b = new int[25], c = new int[5], d = new int[5];
        for (int rc : RC) {
            for (int x = 0; x < 5; x++) c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            for (int x = 0; x < 5; x++) d[x] = c[(x + 4) % 5] ^ Integer.rotateLeft(c[(x + 1) % 5], 1);
            for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) a[x + 5 * y] ^= d[x];
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 5; x++) {
                    int i = x + 5 * y;
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Integer.rotateLeft(a[i], R[i]);
                }
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 5; x++)
                    a[x + 5 * y] = b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y]);
            a[0] ^= rc;
        }
        return a;
    }

    public static byte[] permuteBytes(byte[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length != 100) throw new IllegalArgumentException("100 bytes required");
        return RvnKawpowWords.encode(permute(RvnKawpowWords.decode(input)));
    }
}

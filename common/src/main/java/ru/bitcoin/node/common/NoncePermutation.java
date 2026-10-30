package ru.bitcoin.node.common;

/** Allocation-free bijections. Keys must remain fixed until a logical counter
 * space is exhausted. These functions are scheduling, not cryptography. */
public final class NoncePermutation {
    private NoncePermutation() {}

    public static int mix32(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        return x ^ (x >>> 16);
    }

    public static int unmix32(int x) {
        x ^= x >>> 16;
        x *= 0x43021123;
        x ^= (x >>> 15) ^ (x >>> 30);
        x *= 0x1d69e2a5;
        return x ^ (x >>> 16);
    }

    public static int permute32(int counter, int key) { return mix32(counter + key); }
    public static int inverse32(int nonce, int key) { return unmix32(nonce) - key; }

    public static long permute64(long counter, long key) {
        int low = mix32((int) counter + (int) key);
        int high = mix32((int) (counter >>> 32) ^ low ^ (int) (key >>> 32));
        return ((long) high << 32) | Integer.toUnsignedLong(low);
    }

    public static long inverse64(long nonce, long key) {
        int low = (int) nonce;
        int counterLow = unmix32(low) - (int) key;
        int counterHigh = unmix32((int) (nonce >>> 32)) ^ low ^ (int) (key >>> 32);
        return ((long) counterHigh << 32) | Integer.toUnsignedLong(counterLow);
    }

    /** First eight digest bytes, big endian; identical on the Java/Python boundary. */
    public static long key64(byte[] digest) {
        if (digest.length < 8) throw new IllegalArgumentException("Digest key needs eight bytes");
        long key = 0;
        for (int i = 0; i < 8; i++) key = (key << 8) | (digest[i] & 255L);
        return key;
    }

    public static long mix64(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
}

package ru.bitcoin.node.crypto.hash;

/** SipHash-2-4 as used by BIP152 compact-block short transaction IDs. */
public final class SipHash24 {
    private SipHash24() {}

    public static long hash(long k0, long k1, byte[] data) {
        if (data == null) throw new IllegalArgumentException("data must not be null");
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;
        int end = data.length - (data.length & 7);
        for (int i = 0; i < end; i += 8) {
            long m = read64LE(data, i);
            v3 ^= m;
            long[] s = rounds(v0, v1, v2, v3, 2); v0=s[0]; v1=s[1]; v2=s[2]; v3=s[3];
            v0 ^= m;
        }
        long b = ((long) data.length) << 56;
        for (int i = data.length - 1; i >= end; i--) b |= (data[i] & 0xffL) << (8 * (i - end));
        v3 ^= b;
        long[] s = rounds(v0, v1, v2, v3, 2); v0=s[0]; v1=s[1]; v2=s[2]; v3=s[3];
        v0 ^= b;
        v2 ^= 0xff;
        s = rounds(v0, v1, v2, v3, 4);
        return s[0] ^ s[1] ^ s[2] ^ s[3];
    }

    private static long[] rounds(long v0, long v1, long v2, long v3, int n) {
        for (int i=0;i<n;i++) {
            v0 += v1; v1=Long.rotateLeft(v1,13); v1^=v0; v0=Long.rotateLeft(v0,32);
            v2 += v3; v3=Long.rotateLeft(v3,16); v3^=v2;
            v0 += v3; v3=Long.rotateLeft(v3,21); v3^=v0;
            v2 += v1; v1=Long.rotateLeft(v1,17); v1^=v2; v2=Long.rotateLeft(v2,32);
        }
        return new long[]{v0,v1,v2,v3};
    }

    private static long read64LE(byte[] b, int o) {
        return (b[o]&255L)|((b[o+1]&255L)<<8)|((b[o+2]&255L)<<16)|((b[o+3]&255L)<<24)
                |((b[o+4]&255L)<<32)|((b[o+5]&255L)<<40)|((b[o+6]&255L)<<48)|((b[o+7]&255L)<<56);
    }
}

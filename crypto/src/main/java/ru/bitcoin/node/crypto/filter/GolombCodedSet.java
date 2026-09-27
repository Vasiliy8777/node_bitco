package ru.bitcoin.node.crypto.filter;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.SipHash24;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/** BIP158 Golomb-coded set serializer. */
public final class GolombCodedSet {
    private GolombCodedSet() {}

    public static byte[] encode(Collection<byte[]> elements, long k0, long k1, int p, long m) {
        Objects.requireNonNull(elements, "elements");
        if (p < 0 || p > 63) throw new IllegalArgumentException("p must be in [0,63]");
        if (m <= 0) throw new IllegalArgumentException("m must be positive");
        int n = elements.size();
        if (n == 0) return CompactSize.encode(0);
        long f = Math.multiplyExact((long) n, m);
        long[] values = new long[n];
        int i = 0;
        for (byte[] element : elements) {
            if (element == null) throw new IllegalArgumentException("elements must not contain null");
            long hash = SipHash24.hash(k0, k1, element);
            values[i++] = mapIntoRange(hash, f);
        }
        Arrays.sort(values);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(CompactSize.encode(n));
        BitWriter bits = new BitWriter(out);
        long last = 0;
        long mask = p == 64 ? -1L : ((1L << p) - 1L);
        for (long value : values) {
            long delta = value - last;
            long quotient = delta >>> p;
            long remainder = delta & mask;
            for (long q = 0; q < quotient; q++) bits.writeBit(true);
            bits.writeBit(false);
            bits.writeBits(remainder, p);
            last = value;
        }
        bits.flush();
        return out.toByteArray();
    }

    static long mapIntoRange(long hash, long range) {
        if (range <= 0) throw new IllegalArgumentException("range must be positive");
        return unsignedMultiplyHigh(hash, range);
    }

    /** High 64 bits of the unsigned 128-bit product. */
    static long unsignedMultiplyHigh(long x, long y) {
        long high = Math.multiplyHigh(x, y);
        high += (x >> 63) & y;
        high += (y >> 63) & x;
        return high;
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out;
        private int current;
        private int used;
        private BitWriter(ByteArrayOutputStream out) { this.out = out; }
        void writeBit(boolean one) {
            current = (current << 1) | (one ? 1 : 0);
            if (++used == 8) flushByte();
        }
        void writeBits(long value, int count) {
            for (int bit = count - 1; bit >= 0; bit--) writeBit(((value >>> bit) & 1L) != 0);
        }
        void flush() {
            if (used != 0) {
                current <<= 8 - used;
                flushByte();
            }
        }
        private void flushByte() {
            out.write(current);
            current = 0;
            used = 0;
        }
    }
}

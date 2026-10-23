package ru.bitcoin.node.rvn.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Canonical Bitcoin-family CompactSize integer encoding, used by RVN P2P.
 */
public final class RvnCompactSize {
    private RvnCompactSize() {
    }

    public static void write(OutputStream out, long value) throws IOException {
        if (value < 0) throw new IllegalArgumentException("negative CompactSize");
        if (value < 253) out.write((int) value);
        else if (value <= 0xffffL) {
            out.write(253);
            writeLE(out, value, 2);
        } else if (value <= 0xffffffffL) {
            out.write(254);
            writeLE(out, value, 4);
        } else {
            out.write(255);
            writeLE(out, value, 8);
        }
    }

    public static long read(InputStream in, long max) throws IOException {
        if (max < 0) throw new IllegalArgumentException("max");
        int prefix = in.read();
        if (prefix < 0) throw new EOFException("CompactSize prefix");
        long value;
        if (prefix < 253) value = prefix;
        else if (prefix == 253) {
            value = readLE(in, 2);
            if (value < 253) throw new IOException("Noncanonical CompactSize");
        } else if (prefix == 254) {
            value = readLE(in, 4);
            if (value <= 0xffffL) throw new IOException("Noncanonical CompactSize");
        } else {
            value = readLE(in, 8);
            if (value < 0 || value <= 0xffffffffL) throw new IOException("Invalid/noncanonical CompactSize");
        }
        if (value > max) throw new IOException("CompactSize exceeds limit: " + value);
        return value;
    }

    private static void writeLE(OutputStream out, long value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)) & 255);
    }

    private static long readLE(InputStream in, int bytes) throws IOException {
        long result = 0;
        for (int i = 0; i < bytes; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("Truncated CompactSize");
            result |= (long) b << (8 * i);
        }
        return result;
    }
}

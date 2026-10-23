package ru.bitcoin.node.rvn.protocol;

import java.io.*;
import java.util.Arrays;

/**
 * Wire-format RVN block header. No PoW or chain-validity claim is made.
 */
public final class RvnBlockHeader {
    public static final int LEGACY_SIZE = 80;
    public static final int KAWPOW_SIZE = 120;
    private final byte[] bytes;
    private final boolean kawpow;

    private RvnBlockHeader(byte[] bytes, boolean kawpow) {
        this.bytes = bytes.clone();
        this.kawpow = kawpow;
    }

    public static RvnBlockHeader read(InputStream in, long activationTime) throws IOException {
        byte[] prefix = RvnWire.readFully(in, 76);
        long timestamp = u32(prefix, 68);
        boolean kawpow = timestamp >= activationTime;
        byte[] rest = RvnWire.readFully(in, kawpow ? 44 : 4);
        byte[] all = Arrays.copyOf(prefix, prefix.length + rest.length);
        System.arraycopy(rest, 0, all, prefix.length, rest.length);
        return new RvnBlockHeader(all, kawpow);
    }

    public static RvnBlockHeader parse(byte[] raw, long activationTime) throws IOException {
        if (raw == null) throw new IllegalArgumentException("raw");
        ByteArrayInputStream in = new ByteArrayInputStream(raw);
        RvnBlockHeader header = read(in, activationTime);
        if (in.available() != 0) throw new IOException("Trailing bytes in RVN header");
        return header;
    }

    public byte[] serialize() {
        return bytes.clone();
    }

    public boolean isKawpow() {
        return kawpow;
    }

    public int version() {
        return (int) u32(bytes, 0);
    }

    public byte[] previousHashWire() {
        return Arrays.copyOfRange(bytes, 4, 36);
    }

    public byte[] merkleRootWire() {
        return Arrays.copyOfRange(bytes, 36, 68);
    }

    public long timestamp() {
        return u32(bytes, 68);
    }

    public long compactBits() {
        return u32(bytes, 72);
    }

    public long legacyNonce() {
        if (kawpow) throw new IllegalStateException("KawPoW header has no legacy nonce");
        return u32(bytes, 76);
    }

    public long declaredHeight() {
        if (!kawpow) throw new IllegalStateException("Legacy header has no declared height");
        return u32(bytes, 76);
    }

    public long nonce64Bits() {
        if (!kawpow) throw new IllegalStateException("Legacy header has no nonce64");
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (long) (bytes[80 + i] & 255) << (8 * i);
        return v;
    }

    public byte[] mixHashWire() {
        if (!kawpow) throw new IllegalStateException("Legacy header has no mix hash");
        return Arrays.copyOfRange(bytes, 88, 120);
    }

    /**
     * SHA256d of CKAWPOWInput (80 bytes); NOT the block ID and NOT the final KawPoW hash.
     */
    public byte[] kawpowInputDigest() {
        if (!kawpow) throw new IllegalStateException("Not a KawPoW header");
        return RvnWire.doubleSha(Arrays.copyOf(bytes, 80));
    }

    private static long u32(byte[] b, int offset) {
        return (b[offset] & 255L) | ((b[offset + 1] & 255L) << 8)
                | ((b[offset + 2] & 255L) << 16) | ((b[offset + 3] & 255L) << 24);
    }
}

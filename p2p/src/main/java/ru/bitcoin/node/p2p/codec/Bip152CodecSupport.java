package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.ByteArrayOutputStream;

final class Bip152CodecSupport {
    private Bip152CodecSupport() {
    }

    static void compact(ByteArrayOutputStream out, long v) {
        out.writeBytes(CompactSize.encode(v));
    }

    static void u64(ByteArrayOutputStream out, long v) {
        byte[] b = new byte[8];
        SendCmpctMessageCodec.write64(b, 0, v);
        out.writeBytes(b);
    }

    static long readU64(BitcoinReader r) {
        return r.readInt64LE();
    }

    static void shortId(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 6; i++) out.write((byte) (v >>> (8 * i)));
    }

    static long readShortId(BitcoinReader r) {
        byte[] b = r.readBytes(6);
        long v = 0;
        for (int i = 0; i < 6; i++) v |= (b[i] & 255L) << (8 * i);
        return v;
    }

    static int count(BitcoinReader r, long n, int min, String name) {
        return r.checkedCollectionSize(n, min, name);
    }
}

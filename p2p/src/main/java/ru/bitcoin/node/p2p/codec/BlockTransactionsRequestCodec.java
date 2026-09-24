package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.BlockTransactionsRequest;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.*;
import java.util.*;

public final class BlockTransactionsRequestCodec {
    private BlockTransactionsRequestCodec() {
    }

    public static byte[] encode(BlockTransactionsRequest m) {
        var o = new ByteArrayOutputStream();
        o.writeBytes(m.blockHash().bytes());
        Bip152CodecSupport.compact(o, m.indexes().size());
        int p = -1;
        for (int i : m.indexes()) {
            Bip152CodecSupport.compact(o, (long) i - p - 1);
            p = i;
        }
        return o.toByteArray();
    }

    public static BlockTransactionsRequest decode(byte[] b) {
        var r = new BitcoinReader(b);
        var h = new Hash256(r.readBytes(32));
        long n = r.readCompactSize();
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("index count too large");
        List<Integer> x = new ArrayList<>((int) n);
        long p = -1;
        for (int i = 0; i < n; i++) {
            long d = r.readCompactSize();
            long a = Math.addExact(Math.addExact(p, d), 1);
            if (a > Integer.MAX_VALUE) throw new IllegalArgumentException("index too large");
            x.add((int) a);
            p = a;
        }
        if (r.hasRemaining()) throw new IllegalArgumentException("Unexpected bytes after getblocktxn");
        return new BlockTransactionsRequest(h, x);
    }
}

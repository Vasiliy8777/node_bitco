package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.serialization.*;

import java.io.ByteArrayOutputStream;
import java.util.*;

public final class CompactBlockMessageCodec {
    private CompactBlockMessageCodec() {
    }

    public static byte[] encode(CompactBlockMessage m, long version) {
        if (version != 1 && version != 2) throw new IllegalArgumentException("version");
        var out = new ByteArrayOutputStream();
        out.writeBytes(BlockHeaderSerializer.serialize(m.header()));
        Bip152CodecSupport.u64(out, m.nonce());
        Bip152CodecSupport.compact(out, m.shortIds().size());
        for (long id : m.shortIds()) Bip152CodecSupport.shortId(out, id);
        Bip152CodecSupport.compact(out, m.prefilledTransactions().size());
        int prev = -1;
        for (var p : m.prefilledTransactions()) {
            Bip152CodecSupport.compact(out, (long) p.index() - prev - 1);
            out.writeBytes(version == 2 ? TransactionSerializer.serialize(p.transaction()) : TransactionSerializer.serializeLegacy(p.transaction()));
            prev = p.index();
        }
        return out.toByteArray();
    }

    public static CompactBlockMessage decode(byte[] bytes, long version) {
        if (version != 1 && version != 2) throw new IllegalArgumentException("version");
        var r = new BitcoinReader(bytes);
        BlockHeader h = BlockHeaderParser.parse(r);
        long nonce = Bip152CodecSupport.readU64(r);
        int ns = Bip152CodecSupport.count(r, r.readCompactSize(), 6, "short id count");
        List<Long> ids = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) ids.add(Bip152CodecSupport.readShortId(r));
        long pc = r.readCompactSize();
        if (pc > Integer.MAX_VALUE) throw new IllegalArgumentException("prefilled count too large");
        List<PrefilledTransaction> ps = new ArrayList<>((int) pc);
        long prev = -1;
        for (int i = 0; i < pc; i++) {
            long delta = r.readCompactSize();
            long idx = Math.addExact(Math.addExact(prev, delta), 1);
            if (idx > Integer.MAX_VALUE) throw new IllegalArgumentException("prefilled index too large");
            ps.add(new PrefilledTransaction((int) idx, TransactionParser.parse(r)));
            prev = idx;
        }
        if (r.hasRemaining()) throw new IllegalArgumentException("Unexpected bytes after cmpctblock");
        var m = new CompactBlockMessage(h, nonce, ids, ps);
        if (m.transactionCount() == 0)
            throw new IllegalArgumentException("compact block must describe at least one transaction");
        return m;
    }
}

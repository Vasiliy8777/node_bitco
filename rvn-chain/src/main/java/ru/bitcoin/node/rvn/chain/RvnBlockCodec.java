package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.Transaction;
import java.io.*;
import java.util.*;

/** RVN header framing plus unchanged Bitcoin-family transaction serialization. */
public final class RvnBlockCodec {
    public static final int MAX_BYTES = 8_000_000;
    private RvnBlockCodec() {}

    public static RvnBlock parse(byte[] payload) throws IOException {
        if (payload == null || payload.length > MAX_BYTES) throw new IOException("RVN block payload size");
        try {
            var header = RvnBlockHeader.read(new ByteArrayInputStream(payload), RvnActivation.MAINNET_KAWPOW_TIME);
            var reader = new BitcoinReader(payload);
            reader.readBytes(header.serialize().length);
            int count = reader.checkedCollectionSize(reader.readCompactSize(), 10, "RVN transaction count");
            List<Transaction> transactions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) transactions.add(TransactionParser.parse(reader));
            if (reader.hasRemaining()) throw new IOException("Trailing RVN block bytes");
            return new RvnBlock(header, transactions);
        } catch (IllegalArgumentException e) { throw new IOException("Malformed RVN transaction encoding", e); }
    }

    public static long size(RvnBlock block, boolean witness) {
        long size = block.header().serialize().length + compactSize(block.transactions().size());
        for (var tx : block.transactions()) size = Math.addExact(size, TransactionSerializer.serializedSize(tx, witness));
        return size;
    }
    private static int compactSize(long count) { return count < 253 ? 1 : count <= 65535 ? 3 : 5; }
    public static byte[] serialize(RvnBlock block) {
        if (size(block, true) > MAX_BYTES) throw new IllegalArgumentException("RVN block exceeds wire limit");
        var out = new ByteArrayOutputStream(); out.writeBytes(block.header().serialize());
        try { RvnCompactSize.write(out, block.transactions().size()); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        for (var tx : block.transactions()) out.writeBytes(TransactionSerializer.serialize(tx));
        return out.toByteArray();
    }
}

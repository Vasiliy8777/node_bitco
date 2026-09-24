package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.BlockTransactionsMessage;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.*;
import java.util.*;

public final class BlockTransactionsMessageCodec {
    private BlockTransactionsMessageCodec() {
    }

    public static byte[] encode(BlockTransactionsMessage m, long version) {
        var o = new ByteArrayOutputStream();
        o.writeBytes(m.blockHash().bytes());
        Bip152CodecSupport.compact(o, m.transactions().size());
        for (var tx : m.transactions())
            o.writeBytes(version == 2 ? TransactionSerializer.serialize(tx) : TransactionSerializer.serializeLegacy(tx));
        return o.toByteArray();
    }

    public static BlockTransactionsMessage decode(byte[] b, long version) {
        var r = new BitcoinReader(b);
        var h = new Hash256(r.readBytes(32));
        long n = r.readCompactSize();
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("transaction count too large");
        List<Transaction> txs = new ArrayList<>((int) n);
        for (int i = 0; i < n; i++) txs.add(TransactionParser.parse(r));
        if (r.hasRemaining()) throw new IllegalArgumentException("Unexpected bytes after blocktxn");
        return new BlockTransactionsMessage(h, txs);
    }
}

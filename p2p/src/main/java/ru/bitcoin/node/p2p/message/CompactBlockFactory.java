package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.ByteArrayOutputStream;
import java.util.*;

public final class CompactBlockFactory {
    private CompactBlockFactory() {
    }

    public static CompactBlockMessage create(Block block, long nonce, long version) {
        Objects.requireNonNull(block, "block");
        if (block.transactions().isEmpty()) throw new IllegalArgumentException("block has no transactions");
        if (version != 1 && version != 2) throw new IllegalArgumentException("version");
        byte[] keyMaterial = keyMaterial(block.header(), nonce);
        long k0 = read64(keyMaterial, 0), k1 = read64(keyMaterial, 8);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i < block.transactions().size(); i++) {
            Transaction tx = block.transactions().get(i);
            ids.add(shortId(k0, k1, tx, version));
        }
        return new CompactBlockMessage(block.header(), nonce, ids, List.of(new PrefilledTransaction(0, block.transactions().getFirst())));
    }

    public static byte[] keyMaterial(ru.bitcoin.node.protocol.block.BlockHeader header, long nonce) {
        Objects.requireNonNull(header, "header");
        var o = new ByteArrayOutputStream();
        o.writeBytes(BlockHeaderSerializer.serialize(header));
        byte[] n = new byte[8];
        for (int i = 0; i < 8; i++) n[i] = (byte) (nonce >>> (8 * i));
        o.writeBytes(n);
        return Sha256.hash(o.toByteArray());
    }

    public static long shortId(ru.bitcoin.node.protocol.block.BlockHeader header, long nonce, Transaction transaction, long version) {
        byte[] keyMaterial = keyMaterial(header, nonce);
        return shortId(read64(keyMaterial, 0), read64(keyMaterial, 8), transaction, version);
    }

    private static long shortId(long k0, long k1, Transaction transaction, long version) {
        if (version != 1 && version != 2) throw new IllegalArgumentException("version");
        byte[] hash = (version == 2 ? transaction.wtxId() : transaction.txId()).bytes();
        return SipHash24.hash(k0, k1, hash) & CompactBlockMessage.SHORT_ID_MASK;
    }

    private static long read64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[o + i] & 255L) << (8 * i);
        return v;
    }
}

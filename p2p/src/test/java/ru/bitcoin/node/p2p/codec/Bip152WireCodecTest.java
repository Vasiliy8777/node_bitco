package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.SipHash24;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Bip152WireCodecTest {
    @Test
    void sipHashMatchesReferenceVector() {
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) i;
        long k0 = le(key, 0), k1 = le(key, 8);
        byte[] msg = new byte[15];
        for (int i = 0; i < 15; i++) msg[i] = (byte) i;
        assertEquals(0xa129ca6149be45e5L, SipHash24.hash(k0, k1, msg));
    }

    @Test
    void sendCmpctRoundTrips() {
        var m = new SendCmpctMessage(true, 2);
        assertEquals(m, SendCmpctMessageCodec.decode(SendCmpctMessageCodec.encode(m)));
        assertThrows(IllegalArgumentException.class, () -> SendCmpctMessageCodec.decode(new byte[8]));
    }

    @Test
    void differentialIndexesRoundTrip() {
        var h = Hash256.fromDisplayHex("11".repeat(32));
        var r = new BlockTransactionsRequest(h, List.of(0, 1, 3, 300));
        assertEquals(r, BlockTransactionsRequestCodec.decode(BlockTransactionsRequestCodec.encode(r)));
    }

    @Test
    void compactBlockVersion2RoundTripsWithWitness() {
        var tx = tx(true);
        var block = new Block(header(), List.of(tx, tx(false)));
        var c = CompactBlockFactory.create(block, 0x0102030405060708L, 2);
        assertEquals(1, c.prefilledTransactions().size());
        assertEquals(0, c.prefilledTransactions().getFirst().index());
        assertEquals(1, c.shortIds().size());
        var decoded = CompactBlockMessageCodec.decode(CompactBlockMessageCodec.encode(c, 2), 2);
        assertEquals(c.header(), decoded.header());
        assertEquals(c.nonce(), decoded.nonce());
        assertEquals(c.shortIds(), decoded.shortIds());
        assertEquals(tx.wtxId(), decoded.prefilledTransactions().getFirst().transaction().wtxId());
    }

    @Test
    void blockTransactionsVersion2RoundTrips() {
        var m = new BlockTransactionsMessage(Hash256.fromDisplayHex("22".repeat(32)), List.of(tx(true), tx(false)));
        var d = BlockTransactionsMessageCodec.decode(BlockTransactionsMessageCodec.encode(m, 2), 2);
        assertEquals(m.blockHash(), d.blockHash());
        assertEquals(m.transactions().stream().map(Transaction::wtxId).toList(), d.transactions().stream().map(Transaction::wtxId).toList());
    }

    private static BlockHeader header() {
        return new BlockHeader(4, Hash256.fromDisplayHex("01".repeat(32)), Hash256.fromDisplayHex("02".repeat(32)), new UInt32(3), new UInt32(0x207fffffL), new UInt32(4));
    }

    private static Transaction tx(boolean witness) {
        var in = new TxIn(new OutPoint(Hash256.fromDisplayHex("03".repeat(32)), new UInt32(1)), new byte[]{0x51}, TxIn.FINAL_SEQUENCE, witness ? new Witness(List.of(new byte[]{1, 2, 3})) : Witness.EMPTY);
        return new Transaction(2, List.of(in), List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
    }

    private static long le(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[o + i] & 255L) << (8 * i);
        return v;
    }
}

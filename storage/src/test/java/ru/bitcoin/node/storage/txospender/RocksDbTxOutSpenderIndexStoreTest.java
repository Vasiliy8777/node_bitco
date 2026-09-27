package ru.bitcoin.node.storage.txospender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbTxOutSpenderIndexStoreTest {
    @TempDir Path directory;

    @Test
    void persistsSpenderAndRewindsDisconnectedBlock() {
        OutPoint spent = new OutPoint(hash(1), new UInt32(7));
        Transaction spending = new Transaction(2,
                List.of(new TxIn(spent, new byte[]{1}, new UInt32(0xffff_fffdL), Witness.EMPTY)),
                List.of(new TxOut(1_000, new byte[]{0x51})), new UInt32(0));
        Transaction coinbase = new Transaction(1,
                List.of(new TxIn(OutPoint.coinbase(), new byte[]{1, 1}, new UInt32(0xffff_ffffL), Witness.EMPTY)),
                List.of(new TxOut(5_000_000_000L, new byte[]{0x51})), new UInt32(0));
        Block block = new Block(new BlockHeader(1, hash(2), hash(3), new UInt32(1), new UInt32(0x207fffffL), new UInt32(0)),
                List.of(coinbase, spending));

        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbTxOutSpenderIndexStore(db);
            store.initializeAt(block.header().previousBlockHash());
            store.append(block);
            var found = store.find(spent).orElseThrow();
            assertEquals(spending.txId(), found.transactionId());
            assertEquals(block.hash(), found.blockHash());
            assertEquals(block.hash(), store.bestIndexedBlockHash().orElseThrow());

            store.rewind(block, block.header().previousBlockHash());
            assertTrue(store.find(spent).isEmpty());
            assertEquals(block.header().previousBlockHash(), store.bestIndexedBlockHash().orElseThrow());
        }
    }

    private static Hash256 hash(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return new Hash256(bytes);
    }
}

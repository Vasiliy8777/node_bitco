package ru.bitcoin.node.storage.txindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbTxIndexStoreTest {
    @TempDir Path directory;

    @Test
    void persistsTransactionLocationsAndBestIndexedBlock() {
        var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbTxIndexStore(db);
            store.append(block);
            assertEquals(block.hash(), store.bestIndexedBlockHash().orElseThrow());
            assertEquals(block.hash(), store.findBlockHash(block.transactions().getFirst().txId()).orElseThrow());
            store.clear();
            assertTrue(store.bestIndexedBlockHash().isEmpty());
            assertTrue(store.findBlockHash(block.transactions().getFirst().txId()).isEmpty());
        }
    }
    @Test
    void rewindRemovesDisconnectedMappingsAndMovesCursorAtomically() {
        var block = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        var parent = ru.bitcoin.node.common.types.Hash256.fromDisplayHex("11".repeat(32));
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbTxIndexStore(db);
            store.append(block);
            var txid = block.transactions().getFirst().txId();
            assertEquals(block.hash(), store.findBlockHash(txid).orElseThrow());

            store.rewind(block, parent);

            assertTrue(store.findBlockHash(txid).isEmpty());
            assertEquals(parent, store.bestIndexedBlockHash().orElseThrow());
        }
    }

}

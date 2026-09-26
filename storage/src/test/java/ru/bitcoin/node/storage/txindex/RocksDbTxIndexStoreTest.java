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
}

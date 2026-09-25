package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbFullReindexStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FullReindexerTest {
    private static final NetworkParameters REGTEST = NetworkParametersRegistry.regtest();
    private static final AdjustedTime TIME = () -> 1_800_000_000L;
    @TempDir
    Path directory;

    @Test
    void rebuildsGenesisIndexFromRawBlockNamespace() {
        try (var db = new RocksDbDatabase(directory)) {
            var initialized = new ChainInitializer(db, REGTEST).initialize();
            var indexes = new RocksDbBlockIndexStore(db);
            indexes.delete(initialized.activeTip().hash());

            var reindexer = new FullReindexer(db, REGTEST, TIME);
            var result = reindexer.rebuild();

            assertEquals(GenesisBlockFactory.create(REGTEST).hash(), result.activeTipHash());
            assertEquals(0L, result.activeHeight());
            assertEquals(1L, result.discoveredBlockBodies());
            assertTrue(indexes.find(result.activeTipHash()).isPresent());
            assertFalse(new RocksDbFullReindexStateStore(db).isInProgress());
        }
    }
}

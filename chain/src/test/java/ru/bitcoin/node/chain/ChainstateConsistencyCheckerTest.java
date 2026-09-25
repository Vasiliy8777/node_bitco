package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChainstateConsistencyCheckerTest {
    private static final NetworkParameters REGTEST = NetworkParametersRegistry.regtest();

    @TempDir
    Path tempDirectory;

    @Test
    void emptyDatabaseIsAcceptedWithoutMutation() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("empty"))) {
            var result = new ChainstateConsistencyChecker(database, REGTEST).verify();
            assertEquals(0L, result.activeIndexEntriesChecked());
            assertEquals(0L, result.headerIndexEntriesChecked());
            assertEquals(0L, result.recentBlocksAndUndoChecked());
            assertTrue(database.isEmpty());
        }
    }

    @Test
    void initializedGenesisDatabasePasses() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("genesis"))) {
            var state = new ChainInitializer(database, REGTEST).initialize();
            var result = new ChainstateConsistencyChecker(database, REGTEST).verify();
            assertEquals(1L, result.activeIndexEntriesChecked());
            assertEquals(1L, result.headerIndexEntriesChecked());
            assertEquals(0L, result.recentBlocksAndUndoChecked());
            assertEquals(0L, state.activeTip().height());
        }
    }

    @Test
    void missingActiveTipIndexIsRejected() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("missing-active-index"))) {
            new ChainInitializer(database, REGTEST).initialize();
            Hash256 unknown = Hash256.fromDisplayHex(
                    "1111111111111111111111111111111122222222222222222222222222222222");
            new RocksDbChainStateStore(database).saveActiveTipHash(unknown);
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> new ChainstateConsistencyChecker(database, REGTEST).verify());
            assertTrue(error.getMessage().contains("active tip BlockIndex is missing"));
        }
    }

    @Test
    void missingBestHeaderIndexIsRejected() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("missing-header-index"))) {
            new ChainInitializer(database, REGTEST).initialize();
            Hash256 unknown = Hash256.fromDisplayHex(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            new RocksDbChainStateStore(database).saveBestHeaderTipHash(unknown);
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> new ChainstateConsistencyChecker(database, REGTEST).verify());
            assertTrue(error.getMessage().contains("best-header tip BlockIndex is missing"));
        }
    }

    @Test
    void nonemptyDatabaseWithoutTipMetadataIsRejected() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("partial"))) {
            database.put(new byte[]{0x55, 0x01}, new byte[]{0x01});
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> new ChainstateConsistencyChecker(database, REGTEST).verify());
            assertTrue(error.getMessage().contains("genesis BlockIndex is missing"));
        }
    }
}

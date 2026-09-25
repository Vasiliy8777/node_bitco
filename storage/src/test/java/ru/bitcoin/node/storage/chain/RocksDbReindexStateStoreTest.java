package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbReindexStateStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void markerSurvivesDatabaseRestartAndCanBeCleared() {
        Path path = tempDirectory.resolve("marker");
        Hash256 target = Hash256.fromHex(
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

        try (RocksDbDatabase database = new RocksDbDatabase(path)) {
            var store = new RocksDbReindexStateStore(database);
            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                store.saveTargetTipHash(batch, target);
                database.write(batch);
            }
            assertEquals(target, store.loadTargetTipHash().orElseThrow());
        }

        try (RocksDbDatabase database = new RocksDbDatabase(path)) {
            var store = new RocksDbReindexStateStore(database);
            assertEquals(target, store.loadTargetTipHash().orElseThrow());
            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                store.clear(batch);
                database.write(batch);
            }
            assertTrue(store.loadTargetTipHash().isEmpty());
        }
    }
}

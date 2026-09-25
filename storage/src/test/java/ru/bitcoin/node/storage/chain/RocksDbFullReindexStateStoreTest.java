package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbFullReindexStateStoreTest {
    @TempDir Path directory;

    @Test
    void markerSurvivesDatabaseRestartAndCanBeCleared() {
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbFullReindexStateStore(db);
            assertFalse(store.isInProgress());
            try (var batch = new RocksDbWriteBatch()) {
                store.markInProgress(batch);
                db.write(batch);
            }
            assertTrue(store.isInProgress());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbFullReindexStateStore(db);
            assertTrue(store.isInProgress());
            try (var batch = new RocksDbWriteBatch()) {
                store.clear(batch);
                db.write(batch);
            }
            assertFalse(store.isInProgress());
        }
    }
}

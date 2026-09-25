package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.storage.rocksdb.*;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbPruneStateStoreTest {
    @TempDir
    Path temp;

    @Test
    void persistsHighestPrunedHeightMonotonically() {
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            var store = new RocksDbPruneStateStore(db);
            assertFalse(store.hasPruned());
            try (var batch = new RocksDbWriteBatch()) {
                store.recordHighestPrunedHeight(batch, 100);
                db.write(batch);
            }
            assertEquals(100, store.highestPrunedHeight().orElseThrow());
            try (var batch = new RocksDbWriteBatch()) {
                store.recordHighestPrunedHeight(batch, 50);
                db.write(batch);
            }
            assertEquals(100, store.highestPrunedHeight().orElseThrow());
        }
        try (var db = new RocksDbDatabase(temp.resolve("db"))) {
            assertEquals(100, new RocksDbPruneStateStore(db).highestPrunedHeight().orElseThrow());
        }
    }
}

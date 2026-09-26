package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockValidationMigrationStoreTest {
    @TempDir Path directory;

    @Test
    void persistsEveryMigrationPhaseAcrossReopen() {
        Hash256 cursor = Hash256.fromDisplayHex("42".repeat(32));
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockValidationMigrationStore(db);
            assertEquals(RocksDbBlockValidationMigrationStore.Phase.NOT_STARTED, store.load().phase());
            try (var batch = new RocksDbWriteBatch()) {
                store.saveActive(batch, cursor);
                db.write(batch);
            }
        }
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockValidationMigrationStore(db);
            assertEquals(cursor, store.load().cursor().orElseThrow());
            try (var batch = new RocksDbWriteBatch()) {
                store.saveUndo(batch, cursor);
                db.write(batch);
            }
            assertEquals(RocksDbBlockValidationMigrationStore.Phase.UNDO, store.load().phase());
            try (var batch = new RocksDbWriteBatch()) {
                store.markComplete(batch);
                db.write(batch);
            }
            assertEquals(RocksDbBlockValidationMigrationStore.Phase.COMPLETE, store.load().phase());
        }
    }
}

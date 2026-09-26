package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockValidationStatusStoreTest {
    @TempDir Path directory;

    @Test
    void persistsScriptsValidAndClearsNamespaceAtomically() {
        Hash256 hash = Hash256.fromDisplayHex("11".repeat(32));
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockValidationStatusStore(db);
            assertFalse(store.isScriptsValid(hash));
            try (var batch = new RocksDbWriteBatch()) {
                store.markScriptsValid(batch, hash);
                db.write(batch);
            }
            assertTrue(store.isScriptsValid(hash));
        }
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockValidationStatusStore(db);
            assertTrue(store.isScriptsValid(hash));
            try (var batch = new RocksDbWriteBatch()) {
                store.clear(batch);
                db.write(batch);
            }
            assertFalse(store.isScriptsValid(hash));
        }
    }
}

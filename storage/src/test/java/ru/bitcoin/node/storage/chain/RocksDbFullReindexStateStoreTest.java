package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.common.types.Hash256;

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
    @Test
    void recoveryManifestSurvivesRestart() {
        var active = Hash256.fromDisplayHex("11".repeat(32));
        var bestHeader = Hash256.fromDisplayHex("22".repeat(32));
        var manifest = new RocksDbFullReindexStateStore.Manifest(active, 123L, bestHeader, 130L);
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbFullReindexStateStore(db);
            try (var batch = new RocksDbWriteBatch()) {
                store.markInProgress(batch, manifest);
                db.write(batch);
            }
            assertEquals(manifest, store.manifest().orElseThrow());
        }
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbFullReindexStateStore(db);
            assertTrue(store.isInProgress());
            assertEquals(manifest, store.manifest().orElseThrow());
        }
    }

    @Test
    void legacyMarkerRemainsReadableWithoutInventingRecoveryTarget() {
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbFullReindexStateStore(db);
            try (var batch = new RocksDbWriteBatch()) {
                store.markInProgress(batch);
                db.write(batch);
            }
            assertTrue(store.isInProgress());
            assertTrue(store.manifest().isEmpty());
        }
    }

}

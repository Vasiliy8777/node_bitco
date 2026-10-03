package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbChainstateWriteBackTest {
    @TempDir
    Path dir;

    @Test
    void stagedWritesAreVisibleAndBecomeDurableOnlyAtCheckpoint() {
        byte[] k = {3, 1}, v = {9};
        try (var db = new RocksDbDatabase(dir)) {
            db.enableChainstateWriteBack();
            db.put(k, v);
            assertArrayEquals(v, db.get(k));
            assertEquals(0, db.ioStats().writeBatches());
            db.forceFlushChainstate();
            assertTrue(db.ioStats().writeBatches() >= 1);
            assertArrayEquals(v, db.get(k));
        }
    }
}

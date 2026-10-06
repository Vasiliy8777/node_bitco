package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RocksDbDatabaseTest {
    @Test
    void nativeHintsRemainSafeDuringWritesAndConcurrentClose() throws Exception {
        var db = new RocksDbDatabase(temporaryDirectory.resolve("warm-lifetime"));
        var keys = new ArrayList<byte[]>();
        for (int i = 0; i < 4096; i++) keys.add(new byte[]{3, (byte) (i >> 8), (byte) i});
        db.put(keys.getFirst(), new byte[]{1});
        var started = new java.util.concurrent.CountDownLatch(1);
        var reader = java.util.concurrent.CompletableFuture.runAsync(() -> {
            started.countDown();
            for (int i = 0; i < 20; i++) db.warmKeys(keys);
        });
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) db.put(keys.getFirst(), new byte[]{(byte) i});
            db.enableChainstateWriteBack();
            db.delete(keys.getFirst());
            db.warmKeys(java.util.List.of(keys.getFirst()));
            org.junit.jupiter.api.Assertions.assertNull(db.get(keys.getFirst()));
            db.close();
            reader.get(5, java.util.concurrent.TimeUnit.SECONDS);
            db.warmKeys(keys); // a late cancelled hint is harmless after native disposal
        } finally { db.close(); }
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCreateMissingDatabaseDirectory()
            throws Exception {

        Path databasePath =
                temporaryDirectory
                        .resolve("data")
                        .resolve("regtest");

        assertTrue(
                Files.notExists(
                        databasePath
                )
        );

        try (RocksDbDatabase ignored =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            assertTrue(
                    Files.isDirectory(
                            databasePath
                    )
            );
        }
    }
    @Test
    void shouldVisitOnlyEntriesMatchingCompleteKeyPrefix() {
        try (var db = new RocksDbDatabase(temporaryDirectory.resolve("prefix-scan"))) {
            db.put(new byte[]{0x19, 0x01, 0x02, 0x03}, new byte[]{0x0A});
            db.put(new byte[]{0x19, 0x01, 0x02, 0x04}, new byte[]{0x0B});
            db.put(new byte[]{0x19, 0x01, 0x03, 0x00}, new byte[]{0x0C});
            db.put(new byte[]{0x18, 0x01, 0x02, 0x03}, new byte[]{0x0D});

            List<Integer> values = new ArrayList<>();
            db.forEachEntryByKeyPrefix(new byte[]{0x19, 0x01, 0x02},
                    (key, value) -> values.add(Byte.toUnsignedInt(value[0])));

            assertEquals(List.of(10, 11), values);
        }
    }

    @Test
    void shouldTrackGetsByFirstByteNamespace() {
        try (var db = new RocksDbDatabase(temporaryDirectory.resolve("namespace-telemetry"))) {
            db.put(new byte[]{RocksDbNamespaces.BLOCK_INDEX, 0x01}, new byte[]{0x01});
            db.put(new byte[]{RocksDbNamespaces.UTXO, 0x02}, new byte[]{0x02});

            RocksDbDatabase.NamespaceIoStats before = db.namespaceIoStats();
            db.get(new byte[]{RocksDbNamespaces.BLOCK_INDEX, 0x01});
            db.get(new byte[]{RocksDbNamespaces.BLOCK_INDEX, 0x7f});
            db.get(new byte[]{RocksDbNamespaces.UTXO, 0x02});
            RocksDbDatabase.NamespaceIoStats delta = db.namespaceIoStats().minus(before);

            assertEquals(2L, delta.gets(RocksDbNamespaces.BLOCK_INDEX));
            assertEquals(1L, delta.gets(RocksDbNamespaces.UTXO));
            assertEquals(0L, delta.gets(RocksDbNamespaces.BLOCK));
            assertTrue(delta.getNanos(RocksDbNamespaces.BLOCK_INDEX) >= 0L);
        }
    }
}

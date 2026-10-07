package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockMetadataCacheTest {
    @TempDir Path directory;

    @Test void committedValuesAndMissesAvoidNativeReadsIncludingAfterWriteBackFlush() {
        try (var db = new RocksDbDatabase(directory.resolve("native-reads"))) {
            byte[] present = key(RocksDbNamespaces.BLOCK, 1);
            byte[] absent = key(RocksDbNamespaces.UNDO, 2);
            db.put(present, new byte[]{1});
            assertNull(db.get(absent));
            long nativeBefore = db.readCacheStats().nativeKeys();
            for (int i = 0; i < 128; i++) {
                assertArrayEquals(new byte[]{1}, db.get(present));
                assertNull(db.getAll(List.of(absent, present)).getFirst());
            }
            assertEquals(nativeBefore, db.readCacheStats().nativeKeys());
            db.enableChainstateWriteBack();
            try (var batch = new RocksDbWriteBatch()) {
                batch.deletePrefix(RocksDbNamespaces.BLOCK);
                batch.put(present, new byte[]{2});
                batch.put(absent, new byte[]{3});
                batch.delete(absent);
                db.write(batch);
            }
            assertArrayEquals(new byte[]{2}, db.get(present));
            assertNull(db.get(absent));
            db.disableChainstateWriteBack(true);
            assertArrayEquals(new byte[]{2}, db.getAll(List.of(present, absent)).getFirst());
            assertNull(db.get(absent));
            assertEquals(nativeBefore, db.readCacheStats().nativeKeys());
            assertTrue(db.readCacheStats().metadataHits() >= 384);
            // Oversized legacy payloads must evict a previously cached small value.
            db.put(present, new byte[65]);
            assertEquals(65, db.get(present).length);
            assertEquals(nativeBefore + 1, db.readCacheStats().nativeKeys());
        }
    }

    @Test void metadataRemainsCoherentAcrossBatchesWriteBackPrefixDeletionAndReopen() {
        Path path = directory.resolve("db");
        try (var db = new RocksDbDatabase(path)) {
            for (int prefix : new int[]{4, 5, 7, 16, 43}) {
                byte[] key = key(prefix, 1);
                assertNull(db.get(key)); // cache a miss before inserting
                db.put(key, new byte[]{1});
                assertArrayEquals(new byte[]{1}, db.get(key));
                byte[] exposed = db.get(key); exposed[0] = 99;
                assertArrayEquals(new byte[]{1}, db.getAll(List.of(key)).getFirst());
                try (var abandoned = new RocksDbWriteBatch()) {
                    abandoned.put(key, new byte[]{9});
                    assertArrayEquals(new byte[]{1}, db.get(key));
                }
                try (var batch = new RocksDbWriteBatch()) {
                    batch.deletePrefix((byte) prefix);
                    batch.put(key, new byte[]{2});
                    db.write(batch);
                }
                assertArrayEquals(new byte[]{2}, db.get(key));
                db.enableChainstateWriteBack();
                db.delete(key);
                assertNull(db.getAll(List.of(key)).getFirst());
                db.put(key, new byte[]{3});
                assertArrayEquals(new byte[]{3}, db.get(key));
                db.disableChainstateWriteBack(true);
                assertArrayEquals(new byte[]{3}, db.get(key));
                try (var batch = new RocksDbWriteBatch()) {
                    batch.put(key, new byte[]{4});
                    batch.deletePrefix((byte) prefix);
                    db.write(batch);
                }
                assertNull(db.get(key));
                db.put(key, new byte[]{5});
            }
        }
        try (var db = new RocksDbDatabase(path)) {
            for (int prefix : new int[]{4, 5, 7, 16, 43}) {
                assertArrayEquals(new byte[]{5}, db.get(key(prefix, 1)));
                db.delete(key(prefix, 1));
                assertNull(db.get(key(prefix, 1)));
            }
        }
    }

    @Test void cacheIsBoundedAndRetainsNegativeEntriesWithoutAliasing() {
        var cache = new BlockMetadataCache();
        byte[] first = key(5, 0);
        cache.remember(first, null);
        assertTrue(cache.contains(first));
        for (int i = 1; i <= BlockMetadataCache.CAPACITY; i++) cache.remember(key(5, i), new byte[]{1});
        assertEquals(BlockMetadataCache.CAPACITY, cache.size());
        assertFalse(cache.contains(first));
        byte[] key = key(43, 7); byte[] value = {2};
        cache.remember(key, value); value[0] = 3;
        assertArrayEquals(new byte[]{2}, cache.get(key));
        cache.get(key)[0] = 4;
        assertArrayEquals(new byte[]{2}, cache.get(key));
        cache.invalidatePrefix((byte) 43);
        assertFalse(cache.contains(key));
    }

    private static byte[] key(int prefix, int id) {
        byte[] key = new byte[33]; key[0] = (byte) prefix;
        for (int i = 0; i < 4; i++) key[i + 1] = (byte) (id >>> (8 * i));
        return key;
    }
}

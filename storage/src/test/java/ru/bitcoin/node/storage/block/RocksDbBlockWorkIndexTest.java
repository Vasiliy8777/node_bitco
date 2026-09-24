package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockWorkIndexTest {
    @TempDir Path directory;

    @Test
    void preservesUnsignedWorkHeightAndDisplayHashOrdering() {
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockIndexStore(db);
            var expected = new ArrayList<StoredBlockIndex>();
            for (int i = 0; i < 80; i++) {
                BigInteger work = switch (i % 4) {
                    case 0 -> BigInteger.ZERO;
                    case 1 -> BigInteger.valueOf(255);
                    case 2 -> BigInteger.ONE.shiftLeft(255);
                    default -> BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
                };
                expected.add(index(i, i % 3 == 0 ? Long.MAX_VALUE : i % 3, work));
            }
            try (var batch = new RocksDbWriteBatch()) {
                for (var index : expected) store.save(batch, index);
                db.write(batch);
            }
            expected.sort(Comparator.comparing(StoredBlockIndex::chainWork)
                    .thenComparingLong(StoredBlockIndex::height)
                    .thenComparing(index -> index.hash().toDisplayHex()).reversed());
            var visited = new ArrayList<StoredBlockIndex>();
            assertTrue(store.findBest(index -> { visited.add(index); return false; }).isEmpty());
            assertEquals(expected, visited);
            var checks = new AtomicInteger();
            assertEquals(expected.getFirst(), store.findBest(index -> {
                checks.incrementAndGet(); return true;
            }).orElseThrow());
            assertEquals(1, checks.get());
        }
    }

    @Test
    void batchesAreAtomicAndSupersededOrDeletedEntriesCannotWin() {
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockIndexStore(db);
            var fallback = index(1, 1, BigInteger.TEN);
            var high = index(2, 2, BigInteger.valueOf(100));
            var low = new StoredBlockIndex(high.hash(), high.header(), 2,
                    high.previousBlockHash(), BigInteger.ONE);
            store.save(fallback);
            try (var abandoned = new RocksDbWriteBatch()) {
                store.save(abandoned, high);
                assertEquals(fallback, store.findBest(ignored -> true).orElseThrow());
            }
            assertEquals(fallback, store.findBest(ignored -> true).orElseThrow());
            try (var batch = new RocksDbWriteBatch()) {
                store.save(batch, high);
                store.save(batch, low);
                db.write(batch);
            }
            assertEquals(fallback, store.findBest(ignored -> true).orElseThrow());
            try (var batch = new RocksDbWriteBatch()) {
                store.save(batch, high);
                store.delete(batch, high.hash());
                db.write(batch);
            }
            assertTrue(store.find(high.hash()).isEmpty());
            assertEquals(fallback, store.findBest(ignored -> true).orElseThrow());
            store.save(high);
            assertEquals(high, store.findBest(ignored -> true).orElseThrow());
            store.delete(high.hash());
            assertEquals(fallback, store.findBest(ignored -> true).orElseThrow());
        }
    }

    @Test
    void migratesLegacyPrimaryRecordsAndReusesCompletedIndexAfterRestart() {
        var best = index(1100, 1100, BigInteger.valueOf(1100));
        try (var db = new RocksDbDatabase(directory)) {
            // Old format: only primary block-index records, with a partially populated new index.
            var store = new RocksDbBlockIndexStore(db);
            store.save(index(0, 0, BigInteger.ZERO));
            try (var batch = new RocksDbWriteBatch()) {
                for (int i = 1; i <= 1100; i++) {
                    var index = index(i, i, BigInteger.valueOf(i));
                    byte[] key = new byte[33];
                    key[0] = 1;
                    System.arraycopy(index.hash().bytes(), 0, key, 1, 32);
                    batch.put(key, StoredBlockIndexSerializer.serialize(index));
                }
                db.write(batch);
            }
            assertNull(db.get(new byte[]{9}));
            assertEquals(best, store.findBest(ignored -> true).orElseThrow());
            assertArrayEquals(new byte[]{1}, db.get(new byte[]{9}));
            assertEquals(1101, db.countPrefix((byte) 8));
        }
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockIndexStore(db);
            assertEquals(best, store.findBest(ignored -> true).orElseThrow());
            assertEquals(0, db.namespaceVersion((byte) 8), "Completed index must not be rebuilt on reopen");
            var next = index(1101, 1101, BigInteger.valueOf(1101));
            store.save(next);
            assertEquals(next, store.findBest(ignored -> true).orElseThrow());
        }
    }

    @Test
    void incompleteMigrationDoesNotPublishMarkerAndCanResumeAfterReopen() {
        byte[] corruptKey = new byte[33];
        Arrays.fill(corruptKey, (byte) 0xff);
        corruptKey[0] = 1;
        try (var db = new RocksDbDatabase(directory)) {
            try (var batch = new RocksDbWriteBatch()) {
                for (int i = 0; i < 1100; i++) {
                    var index = index(i, i, BigInteger.valueOf(i));
                    byte[] key = new byte[33];
                    key[0] = 1;
                    System.arraycopy(index.hash().bytes(), 0, key, 1, 32);
                    batch.put(key, StoredBlockIndexSerializer.serialize(index));
                }
                batch.put(corruptKey, new byte[]{0});
                db.write(batch);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new RocksDbBlockIndexStore(db).findBest(ignored -> true));
            assertNull(db.get(new byte[]{9}));
            assertEquals(1024, db.countPrefix((byte) 8));
            // Explicitly repair the injected corruption; migration itself must not discard it.
            db.delete(corruptKey);
        }
        try (var db = new RocksDbDatabase(directory)) {
            assertEquals(index(1099, 1099, BigInteger.valueOf(1099)),
                    new RocksDbBlockIndexStore(db).findBest(ignored -> true).orElseThrow());
            assertArrayEquals(new byte[]{1}, db.get(new byte[]{9}));
            assertEquals(1100, db.countPrefix((byte) 8));
        }
    }

    @Test
    void skipsRejectedCandidatesAndHandlesEmptyOrFullyRejectedIndex() {
        try (var db = new RocksDbDatabase(directory)) {
            var store = new RocksDbBlockIndexStore(db);
            assertTrue(store.findBest(ignored -> true).isEmpty());
            var first = index(1, 1, BigInteger.ONE);
            var second = index(2, 2, BigInteger.TEN);
            store.save(first);
            store.save(second);
            var checks = new AtomicInteger();
            assertEquals(first, store.findBest(index -> {
                checks.incrementAndGet(); return index.equals(first);
            }).orElseThrow());
            assertEquals(2, checks.get());
            assertTrue(store.findBest(ignored -> false).isEmpty());
        }
    }

    private static StoredBlockIndex index(int nonce, long height, BigInteger work) {
        var zero = new Hash256(new byte[32]);
        var header = new BlockHeader(1, zero, zero, new UInt32(1_700_000_000),
                new UInt32(0x207fffff), new UInt32(nonce));
        return new StoredBlockIndex(header.hash(), header, height, zero, work);
    }
}

package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbUtxoStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldSaveAndFindUtxo() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        OutPoint outPoint =
                testOutPoint(0);

        StoredUtxo original =
                testUtxo();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            store.save(
                    outPoint,
                    original
            );

            StoredUtxo restored =
                    store.find(outPoint)
                            .orElseThrow();

            assertEquals(
                    original,
                    restored
            );
        }
    }

    @Test
    void shouldReturnEmptyForUnknownOutPoint() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            Optional<StoredUtxo> result =
                    store.find(
                            testOutPoint(123)
                    );

            assertTrue(
                    result.isEmpty()
            );
        }
    }

    @Test
    void shouldDeleteUtxo() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        OutPoint outPoint =
                testOutPoint(1);

        StoredUtxo utxo =
                testUtxo();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            store.save(
                    outPoint,
                    utxo
            );

            assertTrue(
                    store.find(outPoint)
                            .isPresent()
            );

            store.delete(
                    outPoint
            );

            assertTrue(
                    store.find(outPoint)
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldKeepDifferentOutputIndexesSeparate() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        OutPoint firstOutPoint =
                testOutPoint(0);

        OutPoint secondOutPoint =
                testOutPoint(1);

        StoredUtxo first =
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        StoredUtxo second =
                new StoredUtxo(
                        20_000L,
                        new byte[]{0x52},
                        100L,
                        false
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            store.save(
                    firstOutPoint,
                    first
            );

            store.save(
                    secondOutPoint,
                    second
            );

            assertEquals(
                    first,
                    store.find(firstOutPoint)
                            .orElseThrow()
            );

            assertEquals(
                    second,
                    store.find(secondOutPoint)
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldPersistUtxoAfterDatabaseReopen() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        OutPoint outPoint =
                testOutPoint(2);

        StoredUtxo original =
                testUtxo();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            store.save(
                    outPoint,
                    original
            );
        }

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUtxoStore store =
                    new RocksDbUtxoStore(database);

            StoredUtxo restored =
                    store.find(outPoint)
                            .orElseThrow();

            assertEquals(
                    original,
                    restored
            );
        }
    }

    @Test
    void abandonedBatchPreservesCoinsAndRetryPublishesCommittedCache() {
        for (int capacity : new int[]{0, 2}) {
            Path path = tempDirectory.resolve("atomic-" + capacity);
            var spent = testOutPoint(0);
            var created = testOutPoint(0xffff_ffffL);
            var coin = testUtxo();
            var replacement = new StoredUtxo(12_345L, new byte[]{0x51}, 850_001L, true);
            try (var db = new RocksDbDatabase(path)) {
                var store = new RocksDbUtxoStore(db, (byte) 0x03, capacity);
                store.save(spent, coin);
                var before = store.statistics();
                try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                    store.delete(batch, spent);
                    store.save(batch, created, replacement);
                    assertEquals(coin, store.find(spent).orElseThrow());
                    assertTrue(store.find(created).isEmpty());
                    // Discard the batch after reads have repopulated the cache.
                }
                assertEquals(before.hashSerialized3(), store.statistics().hashSerialized3());
                assertEquals(coin, store.find(spent).orElseThrow());
                assertTrue(store.find(created).isEmpty());
                try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                    store.delete(batch, spent);
                    store.save(batch, created, replacement);
                    assertEquals(coin, store.find(spent).orElseThrow());
                    db.write(batch);
                    store.applyCommittedChanges(new UtxoChanges(java.util.List.of(spent),
                            java.util.List.of(new CreatedUtxo(created, replacement))));
                }
                assertTrue(store.find(spent).isEmpty());
                assertEquals(replacement, store.find(created).orElseThrow());
                assertEquals(1, store.count());
                assertEquals(12_345L, store.statistics().totalAmount());
                assertTrue(store.cacheStats().size() <= capacity);
            }
            try (var db = new RocksDbDatabase(path)) {
                var store = new RocksDbUtxoStore(db);
                assertTrue(store.find(spent).isEmpty());
                assertEquals(replacement, store.find(created).orElseThrow());
                assertEquals(1, store.count());
            }
        }
    }

    @Test
    void abandonedNamespaceClearAndCommittedClearKeepSnapshotIsolated() {
        Path path = tempDirectory.resolve("clear-isolation");
        byte staging = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING;
        var point = testOutPoint(256);
        var snapshotCoin = new StoredUtxo(321L, new byte[]{0x52}, 900_000L, true);
        try (var db = new RocksDbDatabase(path)) {
            var normal = new RocksDbUtxoStore(db, (byte) 0x03, 2);
            var snapshot = new RocksDbUtxoStore(db, staging, 2);
            normal.save(point, testUtxo());
            snapshot.save(point, snapshotCoin);
            var snapshotHash = snapshot.statistics().hashSerialized3();
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                normal.clear(batch);
                assertEquals(testUtxo(), normal.find(point).orElseThrow());
            }
            assertEquals(testUtxo(), normal.find(point).orElseThrow());
            try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                normal.clear(batch);
                db.write(batch);
            }
            assertTrue(normal.find(point).isEmpty());
            assertEquals(0, normal.count());
            assertEquals(snapshotCoin, snapshot.find(point).orElseThrow());
            assertEquals(snapshotHash, snapshot.statistics().hashSerialized3());
            normal.activateNamespace(staging);
            assertEquals(snapshotCoin, normal.find(point).orElseThrow());
            normal.activateNamespace((byte) 0x03);
            assertTrue(normal.find(point).isEmpty());
        }
        try (var db = new RocksDbDatabase(path)) {
            assertEquals(0, new RocksDbUtxoStore(db).count());
            assertEquals(snapshotCoin, new RocksDbUtxoStore(db, staging).find(point).orElseThrow());
        }
    }
    @Test
    void deterministicChurnMatchesModelAcrossNamespacesAndRestarts() {
        var random = new java.util.Random(0xB17C01L);
        var expected = new java.util.ArrayList<java.util.Map<OutPoint, StoredUtxo>>();
        expected.add(new java.util.HashMap<>());
        expected.add(new java.util.HashMap<>());
        byte[] prefixes = {0x03, ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING};
        Path path = tempDirectory.resolve("churn");
        for (int restart = 0; restart < 5; restart++) {
            try (var db = new RocksDbDatabase(path)) {
                var stores = new RocksDbUtxoStore[]{new RocksDbUtxoStore(db, prefixes[0], 7),
                        new RocksDbUtxoStore(db, prefixes[1], 7)};
                for (int ns = 0; ns < 2; ns++) assertModel(stores[ns], expected.get(ns));
                for (int round = 0; round < 200; round++) {
                    int ns = round % 2;
                    var store = stores[ns];
                    var model = expected.get(ns);
                    var next = new java.util.HashMap<>(model);
                    var touched = new java.util.LinkedHashSet<OutPoint>();
                    while (touched.size() < 16) touched.add(testOutPoint(random.nextInt(256)));
                    var spent = new java.util.ArrayList<OutPoint>();
                    var created = new java.util.ArrayList<CreatedUtxo>();
                    try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                        for (var point : touched) {
                            if (random.nextBoolean()) {
                                var coin = new StoredUtxo(random.nextInt(100_000),
                                        new byte[]{0x51, (byte) random.nextInt(256)}, restart * 200L + round,
                                        random.nextBoolean());
                                store.save(batch, point, coin);
                                next.put(point, coin);
                                created.add(new CreatedUtxo(point, coin));
                            } else {
                                store.delete(batch, point);
                                next.remove(point);
                                spent.add(point);
                            }
                            assertEquals(Optional.ofNullable(model.get(point)), store.find(point),
                                    "Pending changes must remain invisible");
                        }
                        if (round % 7 != 0) {
                            db.write(batch);
                            store.applyCommittedChanges(new UtxoChanges(spent, created));
                            model.clear();
                            model.putAll(next);
                        }
                    }
                    for (var point : touched)
                        assertEquals(Optional.ofNullable(model.get(point)), store.find(point));
                    assertTrue(store.cacheStats().size() <= 7);
                    if (round % 40 == 0) {
                        for (int check = 0; check < 2; check++) assertModel(stores[check], expected.get(check));
                    }
                }
                for (int ns = 0; ns < 2; ns++) assertModel(stores[ns], expected.get(ns));
            }
        }
        try (var db = new RocksDbDatabase(path)) {
            for (int ns = 0; ns < 2; ns++) assertModel(new RocksDbUtxoStore(db, prefixes[ns]), expected.get(ns));
        }
    }

    private static void assertModel(RocksDbUtxoStore store, java.util.Map<OutPoint, StoredUtxo> expected) {
        for (int index = 0; index < 256; index++) {
            var point = testOutPoint(index);
            assertEquals(Optional.ofNullable(expected.get(point)), store.find(point));
        }
        assertEquals(expected.size(), store.count());
        var stats = store.statistics();
        assertEquals(expected.size(), stats.txouts());
        assertEquals(expected.values().stream().mapToLong(StoredUtxo::amount).sum(), stats.totalAmount());
        assertTrue(store.cacheStats().size() <= store.cacheStats().capacity());
    }
    private static OutPoint testOutPoint(
            long index
    ) {
        return new OutPoint(
                Hash256.fromDisplayHex(
                        "0123456789abcdef0123456789abcdef" +
                                "0123456789abcdef0123456789abcdef"
                ),
                new UInt32(index)
        );
    }

    private static StoredUtxo testUtxo() {

        return new StoredUtxo(
                50_000L,
                new byte[]{
                        0x00,
                        0x14,
                        0x11,
                        0x22,
                        0x33,
                        0x44
                },
                850_000L,
                false
        );
    }

    @Test
    void computesUtxoSetStatisticsWithoutLoadingWholeSet() {
        try (var database = new ru.bitcoin.node.storage.rocksdb.RocksDbDatabase(tempDirectory.resolve("stats"))) {
            var store = new RocksDbUtxoStore(database);
            store.save(new ru.bitcoin.node.protocol.transaction.OutPoint(
                            ru.bitcoin.node.common.types.Hash256.fromDisplayHex("11".repeat(32)),
                            new ru.bitcoin.node.common.types.UInt32(0)),
                    new StoredUtxo(25_000L, new byte[]{0x51}, 1L, false));
            store.save(new ru.bitcoin.node.protocol.transaction.OutPoint(
                            ru.bitcoin.node.common.types.Hash256.fromDisplayHex("22".repeat(32)),
                            new ru.bitcoin.node.common.types.UInt32(1)),
                    new StoredUtxo(75_000L, new byte[]{0x51, 0x51}, 2L, true));
            var stats = store.statistics();
            org.junit.jupiter.api.Assertions.assertEquals(2L, stats.transactions());
            org.junit.jupiter.api.Assertions.assertEquals(2L, stats.txouts());
            org.junit.jupiter.api.Assertions.assertEquals(100_000L, stats.totalAmount());
            org.junit.jupiter.api.Assertions.assertEquals(103L, stats.bogoSize());
            org.junit.jupiter.api.Assertions.assertTrue(stats.diskSize() > 0L);
        }
    }

    @Test
    void hashSerialized3MatchesCoreSerializationAndSortsVoutNumerically() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("hash-serialized-3"))) {
            var store = new RocksDbUtxoStore(database);
            var txid1 = Hash256.fromDisplayHex(
                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
            var txid2 = Hash256.fromDisplayHex(
                    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
            store.save(new OutPoint(txid1, new UInt32(256)),
                    new StoredUtxo(2_000L, concat(new byte[]{0x00, 0x14}, repeat((byte) 0x22, 20)), 6L, true));
            store.save(new OutPoint(txid1, new UInt32(1)),
                    new StoredUtxo(1_000L, new byte[]{0x51}, 5L, false));
            store.save(new OutPoint(txid2, new UInt32(0)),
                    new StoredUtxo(3_000L, new byte[]{0x6a, 0x01, 0x01}, 7L, false));

            var stats = store.statistics();
            assertEquals(2L, stats.transactions());
            assertEquals(3L, stats.txouts());
            assertEquals(6_000L, stats.totalAmount());
            assertEquals(176L, stats.bogoSize());
            assertEquals("b4c90dcaa592de506ba3fa9b1bd267be85b201e99b569217b4637429162f3686",
                    stats.hashSerialized3().toDisplayHex());

            var muhashStats = store.statistics(RocksDbUtxoStore.HashType.MUHASH);
            assertEquals("282c13404c06fcb032d5031bccc08467e7b55bf13baffae13445b8b41f8e0251",
                    muhashStats.muhash().toDisplayHex());
            assertNull(muhashStats.hashSerialized3());
        }
    }

    private static byte[] repeat(byte value, int count) {
        byte[] result = new byte[count];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }


    @Test
    void isolatedNamespaceCanBecomeActiveWithoutTouchingNormalUtxos() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("namespaces"))) {
            var point = testOutPoint(7);
            var normal = new RocksDbUtxoStore(database);
            normal.save(point, testUtxo());
            var routed = new RocksDbUtxoStore(database, ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.UTXO);
            assertTrue(routed.find(point).isPresent());
            routed.activateNamespace(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING);
            assertTrue(routed.find(point).isEmpty());
            routed.save(point, testUtxo());
            assertEquals(1, routed.count());
            assertTrue(normal.find(point).isPresent());
        }
    }
    @Test
    void boundedReadCacheTracksCommittedCreatesAndSpends() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("read-cache"))) {
            var store = new RocksDbUtxoStore(database, (byte) 0x03, 2);
            var first = testOutPoint(41);
            var second = testOutPoint(42);
            var third = testOutPoint(43);
            var coin = testUtxo();

            store.save(first, coin);
            assertEquals(coin, store.find(first).orElseThrow());
            assertTrue(store.cacheStats().hits() >= 1L);

            store.save(second, coin);
            store.save(third, coin);
            assertTrue(store.cacheStats().size() <= 2);

            store.delete(first);
            assertTrue(store.find(first).isEmpty());
        }
    }

    @Test
    void cacheIsClearedWhenUtxoNamespaceChanges() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("read-cache-namespace"))) {
            var point = testOutPoint(51);
            var store = new RocksDbUtxoStore(database, (byte) 0x03, 8);
            store.save(point, testUtxo());
            assertTrue(store.find(point).isPresent());
            assertTrue(store.cacheStats().size() > 0);

            store.activateNamespace(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING);
            assertEquals(0, store.cacheStats().size());
            assertTrue(store.find(point).isEmpty());
        }
    }

}
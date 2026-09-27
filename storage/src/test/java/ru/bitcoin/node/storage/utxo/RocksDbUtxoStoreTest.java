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

}
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
            org.junit.jupiter.api.Assertions.assertEquals(2L, stats.txouts());
            org.junit.jupiter.api.Assertions.assertEquals(100_000L, stats.totalAmount());
            org.junit.jupiter.api.Assertions.assertEquals(103L, stats.bogoSize());
            org.junit.jupiter.api.Assertions.assertTrue(stats.diskSize() > 0L);
        }
    }

}
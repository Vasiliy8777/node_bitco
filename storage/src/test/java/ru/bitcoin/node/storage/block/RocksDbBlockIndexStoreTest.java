package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockIndexStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void streamsOnlyBlockIndexesAndCanRestartAfterVisitorFailure() {
        try (var database = new RocksDbDatabase(tempDirectory)) {
            var store = new RocksDbBlockIndexStore(database);
            var expected = new java.util.HashSet<StoredBlockIndex>();
            var genesis = storedGenesis();
            for (int i = 0; i < 2000; i++) {
                var original = genesis.header();
                var header = new BlockHeader(original.version(), genesis.hash(), original.merkleRoot(),
                        original.timestamp(), original.bits(), new UInt32(i));
                var index = new StoredBlockIndex(header.hash(), header, i,
                        genesis.hash(), BigInteger.valueOf(i + 1));
                store.save(index);
                expected.add(index);
            }
            database.put(new byte[]{0}, new byte[]{1});
            database.put(new byte[]{2}, new byte[]{1});
            RuntimeException failure = new RuntimeException("visitor failed");
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> store.forEach(index -> { throw failure; })));
            var seen = new java.util.HashSet<StoredBlockIndex>();
            store.forEach(index -> assertTrue(seen.add(index)));
            assertEquals(expected, seen);
        }
    }

    @Test
    void shouldSaveAndFindBlockIndex() {

        Path databasePath =
                tempDirectory.resolve(
                        "block-index"
                );

        StoredBlockIndex original =
                storedGenesis();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(database);

            store.save(original);

            Optional<StoredBlockIndex> result =
                    store.find(
                            original.hash()
                    );

            assertTrue(
                    result.isPresent()
            );

            StoredBlockIndex restored =
                    result.orElseThrow();

            assertEquals(
                    original,
                    restored
            );
        }
    }

    @Test
    void shouldReturnEmptyForUnknownHash() {

        Path databasePath =
                tempDirectory.resolve(
                        "block-index"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(database);

            Optional<StoredBlockIndex> result =
                    store.find(
                            Hash256.fromDisplayHex(
                                    "ffffffffffffffffffffffffffffffff" +
                                            "ffffffffffffffffffffffffffffffff"
                            )
                    );

            assertTrue(
                    result.isEmpty()
            );
        }
    }

    @Test
    void shouldDeleteBlockIndex() {

        Path databasePath =
                tempDirectory.resolve(
                        "block-index"
                );

        StoredBlockIndex original =
                storedGenesis();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(database);

            store.save(original);

            assertTrue(
                    store.find(
                            original.hash()
                    ).isPresent()
            );

            store.delete(
                    original.hash()
            );

            assertTrue(
                    store.find(
                            original.hash()
                    ).isEmpty()
            );
        }
    }

    @Test
    void shouldPersistBlockIndexAfterDatabaseReopen() {

        Path databasePath =
                tempDirectory.resolve(
                        "block-index"
                );

        StoredBlockIndex original =
                storedGenesis();

        /*
         * Первый запуск ноды.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(database);

            store.save(original);
        }

        /*
         * База закрыта.
         *
         * Имитируем новый запуск ноды.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(database);

            StoredBlockIndex restored =
                    store.find(
                            original.hash()
                    ).orElseThrow();

            assertEquals(
                    original,
                    restored
            );
        }
    }

    @Test
    void heightIndexMigratesStreamsInOrderAndTracksDeletes() {
        Path databasePath = tempDirectory.resolve("height-index");
        try (var database = new RocksDbDatabase(databasePath)) {
            var store = new RocksDbBlockIndexStore(database);
            var genesis = storedGenesis();
            store.save(genesis);

            StoredBlockIndex[] byHeight = new StoredBlockIndex[4];
            int[] insertionOrder = {3, 1, 2};
            for (int height : insertionOrder) {
                var original = genesis.header();
                var header = new BlockHeader(original.version(), genesis.hash(), original.merkleRoot(),
                        original.timestamp(), original.bits(), new UInt32(100 + height));
                var index = new StoredBlockIndex(header.hash(), header, height,
                        genesis.hash(), BigInteger.valueOf(1000L + height));
                store.save(index);
                byHeight[height] = index;
            }

            var heights = new java.util.ArrayList<Long>();
            store.visitByHeightAscending(index -> {
                heights.add(index.height());
                return true;
            });
            assertEquals(java.util.List.of(0L, 1L, 2L, 3L), heights);

            store.delete(byHeight[2].hash());
            heights.clear();
            store.visitByHeightAscending(index -> {
                heights.add(index.height());
                return true;
            });
            assertEquals(java.util.List.of(0L, 1L, 3L), heights);

            var stopped = new java.util.ArrayList<Long>();
            store.visitByHeightAscending(index -> {
                stopped.add(index.height());
                return index.height() < 1;
            });
            assertEquals(java.util.List.of(0L, 1L), stopped);
        }
    }

    private static StoredBlockIndex storedGenesis() {

        BlockHeader header =
                genesisHeader();

        return new StoredBlockIndex(
                header.hash(),
                header,
                0,
                header.previousBlockHash(),
                BigInteger.valueOf(
                        4295032833L
                )
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
}

package ru.bitcoin.node.storage.undo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbUndoStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldSaveAndFindUndoData() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Hash256 blockHash =
                testBlockHash();

        BlockUndoData original =
                testUndoData();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            store.save(
                    blockHash,
                    original
            );

            BlockUndoData restored =
                    store.find(blockHash)
                            .orElseThrow();

            assertEquals(
                    original,
                    restored
            );
        }
    }

    @Test
    void shouldReturnEmptyForUnknownBlock() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            Optional<BlockUndoData> result =
                    store.find(
                            testBlockHash()
                    );

            assertTrue(
                    result.isEmpty()
            );
        }
    }

    @Test
    void shouldDeleteUndoData() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Hash256 blockHash =
                testBlockHash();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            store.save(
                    blockHash,
                    testUndoData()
            );

            assertTrue(
                    store.find(blockHash)
                            .isPresent()
            );

            store.delete(
                    blockHash
            );

            assertTrue(
                    store.find(blockHash)
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldPersistAfterDatabaseReopen() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Hash256 blockHash =
                testBlockHash();

        BlockUndoData original =
                testUndoData();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            store.save(
                    blockHash,
                    original
            );
        }

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            assertEquals(
                    original,
                    store.find(blockHash)
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldKeepUndoForDifferentBlocksSeparate() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Hash256 firstHash =
                Hash256.fromDisplayHex(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                );

        Hash256 secondHash =
                Hash256.fromDisplayHex(
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                );

        BlockUndoData first =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                new StoredUtxo(
                                                        1000L,
                                                        new byte[]{0x51},
                                                        100L,
                                                        false
                                                )
                                        )
                                )
                        )
                );

        BlockUndoData second =
                new BlockUndoData(
                        List.of(
                                new TransactionUndo(
                                        List.of(
                                                new StoredUtxo(
                                                        2000L,
                                                        new byte[]{0x52},
                                                        200L,
                                                        false
                                                )
                                        )
                                )
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbUndoStore store =
                    new RocksDbUndoStore(database);

            store.save(
                    firstHash,
                    first
            );

            store.save(
                    secondHash,
                    second
            );

            assertEquals(
                    first,
                    store.find(firstHash)
                            .orElseThrow()
            );

            assertEquals(
                    second,
                    store.find(secondHash)
                            .orElseThrow()
            );
        }
    }

    private static Hash256 testBlockHash() {

        return Hash256.fromDisplayHex(
                "0123456789abcdef0123456789abcdef" +
                        "0123456789abcdef0123456789abcdef"
        );
    }

    private static BlockUndoData testUndoData() {

        TransactionUndo first =
                new TransactionUndo(
                        List.of(
                                new StoredUtxo(
                                        10_000L,
                                        new byte[]{0x51},
                                        100L,
                                        false
                                ),
                                new StoredUtxo(
                                        20_000L,
                                        new byte[]{0x52},
                                        200L,
                                        false
                                )
                        )
                );

        TransactionUndo second =
                new TransactionUndo(
                        List.of(
                                new StoredUtxo(
                                        30_000L,
                                        new byte[]{0x53},
                                        300L,
                                        true
                                )
                        )
                );

        return new BlockUndoData(
                List.of(
                        first,
                        second
                )
        );
    }
}
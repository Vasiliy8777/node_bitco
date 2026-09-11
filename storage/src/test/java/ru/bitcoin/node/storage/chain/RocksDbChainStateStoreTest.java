package ru.bitcoin.node.storage.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbChainStateStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldReturnEmptyWhenActiveTipDoesNotExist() {

        Path databasePath =
                tempDirectory.resolve(
                        "chain-state"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbChainStateStore store =
                    new RocksDbChainStateStore(database);

            Optional<Hash256> result =
                    store.loadActiveTipHash();

            assertTrue(
                    result.isEmpty()
            );
        }
    }

    @Test
    void shouldSaveAndLoadActiveTipHash() {

        Path databasePath =
                tempDirectory.resolve(
                        "chain-state"
                );

        Hash256 hash =
                testHash();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbChainStateStore store =
                    new RocksDbChainStateStore(database);

            store.saveActiveTipHash(
                    hash
            );

            Hash256 restored =
                    store.loadActiveTipHash()
                            .orElseThrow();

            assertEquals(
                    hash,
                    restored
            );
        }
    }

    @Test
    void shouldReplaceActiveTipHash() {

        Path databasePath =
                tempDirectory.resolve(
                        "chain-state"
                );

        Hash256 first =
                Hash256.fromDisplayHex(
                        "11111111111111111111111111111111" +
                                "11111111111111111111111111111111"
                );

        Hash256 second =
                Hash256.fromDisplayHex(
                        "22222222222222222222222222222222" +
                                "22222222222222222222222222222222"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbChainStateStore store =
                    new RocksDbChainStateStore(database);

            store.saveActiveTipHash(
                    first
            );

            store.saveActiveTipHash(
                    second
            );

            assertEquals(
                    second,
                    store.loadActiveTipHash()
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldPersistActiveTipAfterDatabaseReopen() {

        Path databasePath =
                tempDirectory.resolve(
                        "chain-state"
                );

        Hash256 hash =
                testHash();

        /*
         * Первый запуск ноды.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbChainStateStore store =
                    new RocksDbChainStateStore(database);

            store.saveActiveTipHash(
                    hash
            );
        }

        /*
         * Имитация перезапуска ноды.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbChainStateStore store =
                    new RocksDbChainStateStore(database);

            Hash256 restored =
                    store.loadActiveTipHash()
                            .orElseThrow();

            assertEquals(
                    hash,
                    restored
            );
        }
    }

    private static Hash256 testHash() {

        return Hash256.fromDisplayHex(
                "00000000000000000000000000000000" +
                        "00000000000000000000000000000001"
        );
    }
}
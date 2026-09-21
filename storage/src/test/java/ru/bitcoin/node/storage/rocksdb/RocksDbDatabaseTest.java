package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RocksDbDatabaseTest {

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
}
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

}
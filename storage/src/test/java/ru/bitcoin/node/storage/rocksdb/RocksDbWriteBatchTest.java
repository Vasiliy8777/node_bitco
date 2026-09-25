package ru.bitcoin.node.storage.rocksdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbWriteBatchTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldApplyMultiplePutOperations() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        byte[] firstKey = {
                0x01,
                0x01
        };

        byte[] secondKey = {
                0x01,
                0x02
        };

        byte[] firstValue = {
                0x11
        };

        byte[] secondValue = {
                0x22
        };

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath);

             RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            batch.put(
                    firstKey,
                    firstValue
            );

            batch.put(
                    secondKey,
                    secondValue
            );

            /*
             * Batch ещё не применён.
             */
            assertNull(
                    database.get(firstKey)
            );

            assertNull(
                    database.get(secondKey)
            );

            database.write(
                    batch
            );

            assertArrayEquals(
                    firstValue,
                    database.get(firstKey)
            );

            assertArrayEquals(
                    secondValue,
                    database.get(secondKey)
            );
        }
    }

    @Test
    void shouldApplyPutAndDeleteTogether() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        byte[] oldKey = {
                0x10
        };

        byte[] oldValue = {
                0x01
        };

        byte[] newKey = {
                0x20
        };

        byte[] newValue = {
                0x02
        };

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            database.put(
                    oldKey,
                    oldValue
            );

            assertArrayEquals(
                    oldValue,
                    database.get(oldKey)
            );

            try (RocksDbWriteBatch batch =
                         new RocksDbWriteBatch()) {

                batch.delete(
                        oldKey
                );

                batch.put(
                        newKey,
                        newValue
                );

                /*
                 * До commit состояние базы старое.
                 */
                assertArrayEquals(
                        oldValue,
                        database.get(oldKey)
                );

                assertNull(
                        database.get(newKey)
                );

                database.write(
                        batch
                );
            }

            /*
             * После commit оба изменения видны.
             */
            assertNull(
                    database.get(oldKey)
            );

            assertArrayEquals(
                    newValue,
                    database.get(newKey)
            );
        }
    }

    @Test
    void shouldPersistBatchAfterDatabaseReopen() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        byte[] firstKey = {
                0x31
        };

        byte[] secondKey = {
                0x32
        };

        byte[] firstValue = {
                0x41
        };

        byte[] secondValue = {
                0x42
        };

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath);

             RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            batch.put(
                    firstKey,
                    firstValue
            );

            batch.put(
                    secondKey,
                    secondValue
            );

            database.write(
                    batch
            );
        }

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            assertArrayEquals(
                    firstValue,
                    database.get(firstKey)
            );

            assertArrayEquals(
                    secondValue,
                    database.get(secondKey)
            );
        }
    }

    @Test
    void shouldRejectOperationsAfterBatchClose() {

        RocksDbWriteBatch batch =
                new RocksDbWriteBatch();

        batch.close();

        assertThrows(
                IllegalStateException.class,
                () ->
                        batch.put(
                                new byte[]{0x01},
                                new byte[]{0x02}
                        )
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        batch.delete(
                                new byte[]{0x01}
                        )
        );
    }

    @Test
    void shouldAllowCloseTwice() {

        RocksDbWriteBatch batch =
                new RocksDbWriteBatch();

        assertDoesNotThrow(
                batch::close
        );

        assertDoesNotThrow(
                batch::close
        );
    }

    @Test
    void shouldRejectDatabaseOperationsAfterClose() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        RocksDbDatabase database =
                new RocksDbDatabase(
                        databasePath
                );

        database.close();

        assertThrows(
                IllegalStateException.class,
                () ->
                        database.get(
                                new byte[]{0x01}
                        )
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        database.put(
                                new byte[]{0x01},
                                new byte[]{0x02}
                        )
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        database.delete(
                                new byte[]{0x01}
                        )
        );
    }
    @Test
    void shouldDeleteOnlyRequestedNamespaceAtomically() {
        Path databasePath = tempDirectory.resolve("namespace-delete");
        try (RocksDbDatabase database = new RocksDbDatabase(databasePath)) {
            database.put(new byte[]{0x03, 0x01}, new byte[]{0x11});
            database.put(new byte[]{0x03, 0x02}, new byte[]{0x22});
            database.put(new byte[]{0x04, 0x01}, new byte[]{0x33});

            try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
                batch.deletePrefix((byte) 0x03);
                assertArrayEquals(new byte[]{0x11}, database.get(new byte[]{0x03, 0x01}));
                database.write(batch);
            }

            assertNull(database.get(new byte[]{0x03, 0x01}));
            assertNull(database.get(new byte[]{0x03, 0x02}));
            assertArrayEquals(new byte[]{0x33}, database.get(new byte[]{0x04, 0x01}));
        }
    }

    @Test
    void shouldRejectUnsafeFfNamespaceRangeDelete() {
        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            assertThrows(IllegalArgumentException.class, () -> batch.deletePrefix((byte) 0xFF));
        }
    }

}
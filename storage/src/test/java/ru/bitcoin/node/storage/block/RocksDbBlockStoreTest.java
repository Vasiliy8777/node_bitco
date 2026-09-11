package ru.bitcoin.node.storage.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbBlockStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldSaveAndLoadBlock() {

        Path databasePath =
                tempDirectory.resolve("bitcoin");

        Block block =
                block();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            store.save(block);

            Block loaded =
                    store.find(
                                    block.header().hash()
                            )
                            .orElseThrow();

            assertEquals(
                    block,
                    loaded
            );
        }
    }

    @Test
    void shouldReturnEmptyForUnknownBlock() {

        Path databasePath =
                tempDirectory.resolve("unknown");

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            assertTrue(
                    store.find(
                                    Hash256.fromDisplayHex(
                                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                    )
                            )
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldDeleteBlock() {

        Path databasePath =
                tempDirectory.resolve("delete");

        Block block =
                block();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            store.save(block);

            assertTrue(
                    store.find(
                                    block.header().hash()
                            )
                            .isPresent()
            );

            store.delete(
                    block.header().hash()
            );

            assertTrue(
                    store.find(
                                    block.header().hash()
                            )
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldPersistBlockAfterReopen() {

        Path databasePath =
                tempDirectory.resolve("reopen");

        Block block =
                block();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            store.save(block);
        }

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            assertEquals(
                    block,
                    store.find(
                                    block.header().hash()
                            )
                            .orElseThrow()
            );
        }
    }

    @Test
    void shouldSupportBatchSave() {

        Path databasePath =
                tempDirectory.resolve("batch");

        Block block =
                block();

        try (RocksDbDatabase database =
                     new RocksDbDatabase(databasePath)) {

            RocksDbBlockStore store =
                    new RocksDbBlockStore(database);

            try (RocksDbWriteBatch batch =
                         new RocksDbWriteBatch()) {

                store.save(
                        batch,
                        block
                );

                assertTrue(
                        store.find(
                                        block.header().hash()
                                )
                                .isEmpty()
                );

                database.write(batch);
            }

            assertEquals(
                    block,
                    store.find(
                                    block.header().hash()
                            )
                            .orElseThrow()
            );
        }
    }

    private static Block block() {

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "00000000000000000000000000000000" +
                                        "00000000000000000000000000000000"
                        ),
                        Hash256.fromDisplayHex(
                                "4a5e1e4baab89f3a32518a88c31bc87" +
                                        "f618f76673e2cc77ab2127b7afdeda33b"
                        ),
                        new UInt32(
                                1231006505L
                        ),
                        new UInt32(
                                0x1D00FFFFL
                        ),
                        new UInt32(
                                2083236893L
                        )
                );

        return new Block(
                header,
                List.of()
        );
    }
}
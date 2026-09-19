package ru.bitcoin.node.chain.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.math.BigInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownHeaderStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPersistHeaderIndexWithoutUpdatingBestHeaderTip() {

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "header-only"
                             )
                     )) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            KnownHeaderStorage storage =
                    new KnownHeaderStorage(
                            database,
                            blockIndexStore,
                            chainStateStore
                    );

            BlockIndex blockIndex =
                    blockIndex(
                            1L,
                            BigInteger.valueOf(
                                    100
                            )
                    );

            storage.save(
                    blockIndex,
                    false
            );

            assertTrue(
                    blockIndexStore
                            .find(
                                    blockIndex.hash()
                            )
                            .isPresent()
            );

            assertTrue(
                    chainStateStore
                            .loadBestHeaderTipHash()
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldPersistHeaderIndexAndBestHeaderTipTogether() {

        Path databasePath =
                tempDirectory.resolve(
                        "best-header"
                );

        BlockIndex blockIndex =
                blockIndex(
                        2L,
                        BigInteger.valueOf(
                                200
                        )
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            KnownHeaderStorage storage =
                    new KnownHeaderStorage(
                            database,
                            blockIndexStore,
                            chainStateStore
                    );

            storage.save(
                    blockIndex,
                    true
            );
        }

        /*
         * Reopen the database so the assertions
         * verify persistent state, not merely
         * the currently opened store objects.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            RocksDbBlockIndexStore blockIndexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            assertTrue(
                    blockIndexStore
                            .find(
                                    blockIndex.hash()
                            )
                            .isPresent()
            );

            assertEquals(
                    blockIndex.hash(),
                    chainStateStore
                            .loadBestHeaderTipHash()
                            .orElseThrow()
            );
        }
    }

    private static BlockIndex blockIndex(
            long height,
            BigInteger chainWork
    ) {

        Hash256 previousBlockHash =
                Hash256.fromDisplayHex(
                        "%064x".formatted(
                                height
                        )
                );

        BlockHeader header =
                new BlockHeader(
                        4,
                        previousBlockHash,
                        Hash256.fromDisplayHex(
                                "%064x".formatted(
                                        height + 1
                                )
                        ),
                        new UInt32(
                                1_700_000_000L
                                        + height
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                height
                        )
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousBlockHash,
                chainWork
        );
    }
}
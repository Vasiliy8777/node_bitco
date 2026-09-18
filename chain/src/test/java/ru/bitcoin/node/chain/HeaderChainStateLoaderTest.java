package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderChainStateLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldReturnEmptyWhenBestHeaderTipDoesNotExist() {

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "headers"
                             )
                     )) {

            HeaderChainStateLoader loader =
                    new HeaderChainStateLoader(
                            new RocksDbBlockIndexStore(
                                    database
                            ),
                            new RocksDbChainStateStore(
                                    database
                            )
                    );

            Optional<HeaderChainState> result =
                    loader.load();

            assertTrue(
                    result.isEmpty()
            );
        }
    }

    @Test
    void shouldLoadBestHeaderTip() {

        Path databasePath =
                tempDirectory.resolve(
                        "headers"
                );

        Hash256 expectedHash;

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            var genesis =
                    GenesisBlockFactory.create(
                            NetworkParametersRegistry.regtest()
                    );

            BlockIndex genesisIndex =
                    BlockIndexFactory.createGenesis(
                            genesis.header()
                    );

            expectedHash =
                    genesisIndex.hash();

            RocksDbBlockIndexStore indexes =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore tips =
                    new RocksDbChainStateStore(
                            database
                    );

            indexes.save(
                    BlockIndexStorageMapper.toStored(
                            genesisIndex
                    )
            );

            tips.saveBestHeaderTipHash(
                    expectedHash
            );
        }

        /*
         * Reopen the database to verify that
         * HeaderChainState is reconstructed
         * from persistent state.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            HeaderChainStateLoader loader =
                    new HeaderChainStateLoader(
                            new RocksDbBlockIndexStore(
                                    database
                            ),
                            new RocksDbChainStateStore(
                                    database
                            )
                    );

            HeaderChainState state =
                    loader.load()
                            .orElseThrow();

            assertEquals(
                    expectedHash,
                    state.bestHeaderTip()
                            .hash()
            );
        }
    }

    @Test
    void shouldRejectBestHeaderTipWithMissingBlockIndex() {

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             tempDirectory.resolve(
                                     "headers"
                             )
                     )) {

            Hash256 missingHash =
                    Hash256.fromDisplayHex(
                            "11".repeat(32)
                    );

            RocksDbChainStateStore tips =
                    new RocksDbChainStateStore(
                            database
                    );

            tips.saveBestHeaderTipHash(
                    missingHash
            );

            HeaderChainStateLoader loader =
                    new HeaderChainStateLoader(
                            new RocksDbBlockIndexStore(
                                    database
                            ),
                            tips
                    );

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            loader::load
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    missingHash.toDisplayHex()
                            )
            );
        }
    }
}
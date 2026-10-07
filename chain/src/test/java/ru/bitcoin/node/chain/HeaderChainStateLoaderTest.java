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
    void sharesIndexButStillObservesTipChangesRollbackAndMissingMetadata() {
        try (var database = new RocksDbDatabase(tempDirectory.resolve("shared-index"))) {
            var indexes = new RocksDbBlockIndexStore(database);
            var tips = new RocksDbChainStateStore(database);
            BlockIndex genesis = BlockIndexFactory.createGenesis(
                    GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header());
            var first = BlockIndexFactory.createChild(genesis, new ru.bitcoin.node.protocol.block.BlockHeader(
                    4, genesis.hash(), genesis.hash(), genesis.header().timestamp(), genesis.header().bits(),
                    new ru.bitcoin.node.common.types.UInt32(1)));
            var other = BlockIndexFactory.createChild(genesis, new ru.bitcoin.node.protocol.block.BlockHeader(
                    4, genesis.hash(), genesis.hash(), genesis.header().timestamp(), genesis.header().bits(),
                    new ru.bitcoin.node.common.types.UInt32(2)));
            for (var index : java.util.List.of(genesis, first, other))
                indexes.save(BlockIndexStorageMapper.toStored(index));
            tips.saveBestHeaderTipHash(genesis.hash());
            byte prefix = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_INDEX;
            var before = database.namespaceIoStats();
            var legacy = new HeaderChainStateLoader(indexes, tips).load().orElseThrow();
            for (int i = 0; i < 128; i++) assertEquals(genesis.hash(), legacy.bestHeaderTip().hash());
            assertEquals(129, database.namespaceIoStats().minus(before).gets(prefix));
            before = database.namespaceIoStats();
            var shared = new HeaderChainStateLoader(indexes, tips,
                    new StoredBlockIndexLookup(indexes)).load().orElseThrow();
            for (int i = 0; i < 128; i++) assertEquals(genesis.hash(), shared.bestHeaderTip().hash());
            assertEquals(1, database.namespaceIoStats().minus(before).gets(prefix));
            tips.saveBestHeaderTipHash(first.hash());
            assertEquals(first.hash(), shared.bestHeaderTip().hash());
            tips.saveBestHeaderTipHash(other.hash());
            assertEquals(other.hash(), shared.bestHeaderTip().hash());
            tips.saveBestHeaderTipHash(genesis.hash());
            assertEquals(genesis.hash(), shared.bestHeaderTip().hash());
            tips.saveBestHeaderTipHash(Hash256.fromDisplayHex("11".repeat(32)));
            assertThrows(IllegalStateException.class, shared::bestHeaderTip);
        }
    }

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

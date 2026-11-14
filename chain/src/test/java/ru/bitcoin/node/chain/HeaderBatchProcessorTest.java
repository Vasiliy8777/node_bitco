package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderBatchProcessorTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;

    @TempDir
    Path tempDirectory;

    @Test
    void rejectsExplicitFailuresButPreservesKnownDescendantsAndReconsideration() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("failed-headers"))) {
            var store = new RocksDbBlockIndexStore(database);
            var stateStore = new RocksDbChainStateStore(database);
            var failures = new ru.bitcoin.node.storage.block.RocksDbBlockFailureStore(database);
            var genesis = createGenesisIndex();
            store.save(BlockIndexStorageMapper.toStored(genesis));
            stateStore.saveBestHeaderTipHash(genesis.hash());
            var lookup = new StoredBlockIndexLookup(store);
            var state = new HeaderChainState(genesis);
            var processor = new HeaderBatchProcessor(new HeaderProcessor(lookup, REGTEST, TEST_TIME),
                    state, new KnownHeaderStorage(database, store, stateStore),
                    new BlockFailureResolver(lookup, failures));
            var first = findValidHeader(genesis.hash(), 1_700_000_001L);
            var knownChild = findValidHeader(first.hash(), 1_700_000_002L);
            processor.process(List.of(first, knownChild));
            failures.markFailed(first.hash());
            var duplicate = org.junit.jupiter.api.Assertions.assertThrows(
                    ru.bitcoin.node.consensus.block.BlockHeaderValidationException.class,
                    () -> processor.process(List.of(first)));
            assertTrue(duplicate.getMessage().startsWith("duplicate-invalid"));
            var newChild = findValidHeader(first.hash(), 1_700_000_003L);
            var prefix = findValidHeader(genesis.hash(), 1_700_000_004L);
            var callbacks = new java.util.ArrayList<BlockIndex>();
            var invalidParent = org.junit.jupiter.api.Assertions.assertThrows(
                    ru.bitcoin.node.consensus.block.BlockHeaderValidationException.class,
                    () -> processor.process(List.of(prefix, newChild), callbacks::add));
            assertTrue(invalidParent.getMessage().startsWith("bad-prevblk"));
            assertEquals(1, callbacks.size());
            assertTrue(store.find(prefix.hash()).isEmpty());
            assertTrue(store.find(newChild.hash()).isEmpty());
            // Core 32.1 SetBlockFailureFlags marks descendants too; cached headers remain stored but are rejected.
            var failedChild = org.junit.jupiter.api.Assertions.assertThrows(
                    ru.bitcoin.node.consensus.block.BlockHeaderValidationException.class,
                    () -> processor.process(List.of(knownChild)));
            assertEquals("duplicate-invalid", failedChild.rejectReason());
            assertTrue(store.find(knownChild.hash()).isPresent());
            failures.clearFailed(first.hash());
            assertEquals(first.hash(), processor.process(List.of(first)).getFirst().hash());
            assertEquals(knownChild.hash(), processor.process(List.of(knownChild)).getFirst().hash());
            assertEquals(newChild.hash(), processor.process(List.of(newChild)).getFirst().hash());
        }
    }

    @Test
    void shouldProcessAndPersistSequentialHeaders() {

        Path databasePath =
                tempDirectory.resolve(
                        "headers"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            RocksDbBlockIndexStore store =
                    new RocksDbBlockIndexStore(
                            database
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            BlockIndex genesis =
                    createGenesisIndex();

            store.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            chainStateStore.saveBestHeaderTipHash(
                    genesis.hash()
            );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            genesis
                    );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            store,
                            chainStateStore
                    );

            StoredBlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            store
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            BlockHeader header1 =
                    findValidHeader(
                            genesis.hash(),
                            1_700_000_001L
                    );

            BlockHeader header2 =
                    findValidHeader(
                            header1.hash(),
                            1_700_000_002L
                    );

            BlockHeader header3 =
                    findValidHeader(
                            header2.hash(),
                            1_700_000_003L
                    );

            List<BlockIndex> result =
                    batchProcessor.process(
                            List.of(
                                    header1,
                                    header2,
                                    header3
                            )
                    );

            assertEquals(
                    3,
                    result.size()
            );

            assertEquals(
                    1L,
                    result.get(0).height()
            );

            assertEquals(
                    2L,
                    result.get(1).height()
            );

            assertEquals(
                    3L,
                    result.get(2).height()
            );

            assertEquals(
                    genesis.hash(),
                    result.get(0)
                            .previousBlockHash()
            );

            assertEquals(
                    result.get(0).hash(),
                    result.get(1)
                            .previousBlockHash()
            );

            assertEquals(
                    result.get(1).hash(),
                    result.get(2)
                            .previousBlockHash()
            );

            assertTrue(
                    store.find(
                            header1.hash()
                    ).isPresent()
            );

            assertTrue(
                    store.find(
                            header2.hash()
                    ).isPresent()
            );

            assertTrue(
                    store.find(
                            header3.hash()
                    ).isPresent()
            );

            assertEquals(
                    3L,
                    lookup.find(
                            header3.hash()
                    ).height()
            );

            assertEquals(
                    result.get(2).hash(),
                    headerChainState
                            .bestHeaderTip()
                            .hash()
            );

            assertEquals(
                    result.get(2).hash(),
                    chainStateStore
                            .loadBestHeaderTipHash()
                            .orElseThrow()
            );
        }
    }

    private static BlockIndex createGenesisIndex() {

        Hash256 previous =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        BlockHeader header =
                findValidHeader(
                        previous,
                        1_700_000_000L
                );

        return new BlockIndex(
                header.hash(),
                header,
                0L,
                previous,
                BigInteger.ONE
        );
    }

    private static BlockHeader findValidHeader(
            Hash256 previousBlockHash,
            long timestamp
    ) {

        for (long nonce = 0;
             nonce <= UInt32.MAX_VALUE;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            previousBlockHash,
                            Hash256.fromDisplayHex(
                                    "11".repeat(32)
                            ),
                            new UInt32(
                                    timestamp
                            ),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    nonce
                            )
                    );

            if (ProofOfWork.isValid(
                    header,
                    REGTEST
            )) {
                return header;
            }
        }

        throw new IllegalStateException(
                "Could not find valid regtest nonce"
        );
    }
}

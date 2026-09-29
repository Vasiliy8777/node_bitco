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
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnownHeaderStorageTest {

    @Test
    void singleSavePersistsSkipAndAllIndexesAcrossReopen() {
        Path path = tempDirectory.resolve("single-skip");
        BlockIndex genesis = blockIndex(0, null, BigInteger.ONE);
        BlockIndex child = blockIndex(1, genesis, BigInteger.TWO);
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            var storage = new KnownHeaderStorage(db, indexes, new RocksDbChainStateStore(db));
            storage.save(genesis, true);
            storage.save(child, true);
        }
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            assertEquals(genesis.hash(), indexes.findSkipHash(child.hash()).orElseThrow());
            assertEquals(child.hash(), indexes.findBest(index -> true).orElseThrow().hash());
            var heights = new java.util.ArrayList<Long>();
            indexes.visitByHeightAscending(index -> { heights.add(index.height()); return true; });
            assertEquals(java.util.List.of(0L, 1L), heights);
            assertEquals(child.hash(), new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
        }
    }

    @Test
    void incompleteResolvedSkipsRollBackEveryIndexAndTip() {
        verifyRejectedBatch(true);
    }

    @Test
    void missingAncestorRollsBackEveryIndexAndTip() {
        verifyRejectedBatch(false);
    }

    @Test
    void singleSaveRejectsMissingParentWithoutChangingTip() {
        try (var db = new RocksDbDatabase(tempDirectory.resolve("missing-parent"))) {
            var indexes = new RocksDbBlockIndexStore(db);
            var state = new RocksDbChainStateStore(db);
            var storage = new KnownHeaderStorage(db, indexes, state);
            BlockIndex genesis = blockIndex(0, null, BigInteger.ONE);
            storage.save(genesis, true);
            BlockIndex missing = blockIndex(1, genesis, BigInteger.TWO);
            BlockIndex child = blockIndex(2, missing, BigInteger.TEN);
            assertThrows(IllegalStateException.class, () -> storage.save(child, true));
            assertTrue(indexes.find(child.hash()).isEmpty());
            assertEquals(genesis.hash(), state.loadBestHeaderTipHash().orElseThrow());
        }
    }

    @Test
    void batchRejectsAncestorHeightJumpWithoutCommitting() {
        try (var db = new RocksDbDatabase(tempDirectory.resolve("height-jump"))) {
            var indexes = new RocksDbBlockIndexStore(db);
            var storage = new KnownHeaderStorage(db, indexes, new RocksDbChainStateStore(db));
            BlockIndex genesis = blockIndex(0, null, BigInteger.ONE);
            storage.save(genesis, true);
            BlockIndex child = blockIndex(2, genesis, BigInteger.TWO);
            assertThrows(IllegalStateException.class, () -> storage.saveBatch(java.util.List.of(child), child));
            assertTrue(indexes.find(child.hash()).isEmpty());
        }
    }

    @Test
    void resolvedBatchPersistsAllIndexesAcrossReopen() {
        Path path = tempDirectory.resolve("resolved-batch");
        BlockIndex genesis = blockIndex(0, null, BigInteger.ONE);
        BlockIndex first = blockIndex(1, genesis, BigInteger.TWO);
        BlockIndex second = blockIndex(2, first, BigInteger.TEN);
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            var storage = new KnownHeaderStorage(db, indexes, new RocksDbChainStateStore(db));
            storage.save(genesis, true);
            storage.saveBatch(java.util.List.of(first, second), second,
                    java.util.Map.of(first.hash(), genesis.hash(), second.hash(), genesis.hash()));
        }
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            assertEquals(3, indexes.findAll().size());
            assertEquals(genesis.hash(), indexes.findSkipHash(first.hash()).orElseThrow());
            assertEquals(genesis.hash(), indexes.findSkipHash(second.hash()).orElseThrow());
            assertEquals(3, db.valuesByPrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_WORK_INDEX).size());
            assertEquals(3, db.valuesByPrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_HEIGHT_INDEX).size());
            assertEquals(second.hash(), indexes.findBest(index -> true).orElseThrow().hash());
            assertEquals(second.hash(), new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
        }
    }

    private void verifyRejectedBatch(boolean resolved) {
        Path path = tempDirectory.resolve("rejected-batch");
        BlockIndex genesis = blockIndex(0, null, BigInteger.ONE);
        BlockIndex first = blockIndex(1, genesis, BigInteger.TWO);
        BlockIndex second = blockIndex(2, resolved ? first : blockIndex(1, null, BigInteger.TWO), BigInteger.TEN);
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            var state = new RocksDbChainStateStore(db);
            var storage = new KnownHeaderStorage(db, indexes, state);
            storage.save(genesis, true);
            assertThrows(IllegalStateException.class, () -> storage.saveBatch(
                    java.util.List.of(first, second), second,
                    resolved ? java.util.Map.of(first.hash(), genesis.hash()) : null));
            assertEquals(genesis.hash(), state.loadBestHeaderTipHash().orElseThrow());
            assertTrue(indexes.find(first.hash()).isEmpty());
        }
        try (var db = new RocksDbDatabase(path)) {
            var indexes = new RocksDbBlockIndexStore(db);
            assertEquals(1, indexes.findAll().size());
            assertTrue(indexes.findSkipHash(first.hash()).isEmpty());
            assertTrue(indexes.findSkipHash(second.hash()).isEmpty());
            // Inspect raw secondary namespaces: findBest/height visitors filter stale entries.
            assertEquals(1, db.valuesByPrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_WORK_INDEX).size());
            assertEquals(1, db.valuesByPrefix(ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.BLOCK_HEIGHT_INDEX).size());
            assertEquals(genesis.hash(), new RocksDbChainStateStore(db).loadBestHeaderTipHash().orElseThrow());
        }
    }

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
                            0L,
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
                        0L,
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

    @Test
    void shouldPersistValidatedHeaderBatchAndBestTipAtomically() {
        try (RocksDbDatabase database = new RocksDbDatabase(tempDirectory.resolve("header-batch"))) {
            RocksDbBlockIndexStore blockIndexStore = new RocksDbBlockIndexStore(database);
            RocksDbChainStateStore chainStateStore = new RocksDbChainStateStore(database);
            KnownHeaderStorage storage = new KnownHeaderStorage(database, blockIndexStore, chainStateStore);

            BlockIndex previous = null;
            for (long height = 0; height < 10; height++) {
                BlockIndex current = blockIndex(
                        height,
                        previous,
                        BigInteger.valueOf((height + 1) * 100)
                );
                storage.save(current, false);
                previous = current;
            }

            BlockIndex first = blockIndex(10L, previous, BigInteger.valueOf(1_100));
            BlockIndex second = blockIndex(11L, first, BigInteger.valueOf(1_200));

            storage.saveBatch(java.util.List.of(first, second), second);

            assertTrue(blockIndexStore.find(first.hash()).isPresent());
            assertTrue(blockIndexStore.find(second.hash()).isPresent());
            assertEquals(second.hash(), chainStateStore.loadBestHeaderTipHash().orElseThrow());
        }
    }

    private static BlockIndex blockIndex(
            long height,
            BlockIndex previous,
            BigInteger chainWork
    ) {
        Hash256 previousBlockHash =
                previous == null
                        ? Hash256.fromDisplayHex(
                        "0".repeat(64)
                )
                        : previous.hash();

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

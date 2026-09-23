package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockFailureStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockFailureRecoveryTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;

    @TempDir
    Path tempDirectory;

    @Test
    void failedHighWorkBranchFallsBackSurvivesRestartAndCannotRegainBestHeader() {

        Path path =
                tempDirectory.resolve(
                        "failed-branch-restart"
                );

        Hash256 validTipHash;
        Hash256 failedHash;
        Hash256 failedDescendantHash;

        /*
         * Первый запуск.
         *
         * Создаём:
         *
         * genesis
         *   ├── valid1 -> valid2
         *   │
         *   └── failed1 -> failed2 -> failed3
         *
         * failed3 является текущим best header.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(path)) {

            var indexes =
                    new RocksDbBlockIndexStore(
                            database
                    );

            var tips =
                    new RocksDbChainStateStore(
                            database
                    );

            var failures =
                    new RocksDbBlockFailureStore(
                            database
                    );

            var lookup =
                    new StoredBlockIndexLookup(
                            indexes
                    );

            var resolver =
                    new BlockFailureResolver(
                            lookup,
                            failures
                    );

            BlockIndex genesis =
                    BlockIndexFactory.createGenesis(
                            GenesisBlockFactory
                                    .create(REGTEST)
                                    .header()
                    );

            indexes.save(
                    BlockIndexStorageMapper.toStored(
                            genesis
                    )
            );

            BlockIndex valid1 =
                    child(
                            genesis,
                            1_700_000_001L,
                            0x11
                    );

            BlockIndex valid2 =
                    child(
                            valid1,
                            1_700_000_002L,
                            0x12
                    );

            BlockIndex failed1 =
                    child(
                            genesis,
                            1_700_000_003L,
                            0x21
                    );

            BlockIndex failed2 =
                    child(
                            failed1,
                            1_700_000_004L,
                            0x22
                    );

            BlockIndex failed3 =
                    child(
                            failed2,
                            1_700_000_005L,
                            0x23
                    );

            save(
                    indexes,
                    valid1,
                    valid2,
                    failed1,
                    failed2,
                    failed3
            );

            /*
             * До обнаружения invalid body эта ветка
             * считается best-header branch.
             */
            tips.saveBestHeaderTipHash(
                    failed3.hash()
            );

            var manager =
                    new BlockFailureManager(
                            database,
                            failures,
                            indexes,
                            tips,
                            resolver
                    );

            /*
             * Выясняется, что failed1 consensus-invalid.
             *
             * FAILED должен распространиться логически
             * на failed2/failed3, а best-header должен
             * откатиться на strongest eligible branch.
             */
            manager.markFailed(
                    failed1.hash()
            );

            assertTrue(
                    failures.isFailed(
                            failed1.hash()
                    )
            );

            assertTrue(
                    resolver.isFailed(
                            failed1
                    )
            );

            assertTrue(
                    resolver.isFailed(
                            failed2
                    )
            );

            assertTrue(
                    resolver.isFailed(
                            failed3
                    )
            );

            assertFalse(
                    resolver.isFailed(
                            valid2
                    )
            );

            assertEquals(
                    valid2.hash(),
                    tips.loadBestHeaderTipHash()
                            .orElseThrow()
            );

            validTipHash =
                    valid2.hash();

            failedHash =
                    failed1.hash();

            failedDescendantHash =
                    failed3.hash();
        }

        /*
         * Полный restart RocksDB/runtime state.
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(path)) {

            var indexes =
                    new RocksDbBlockIndexStore(
                            database
                    );

            var tips =
                    new RocksDbChainStateStore(
                            database
                    );

            var failures =
                    new RocksDbBlockFailureStore(
                            database
                    );

            var lookup =
                    new StoredBlockIndexLookup(
                            indexes
                    );

            var resolver =
                    new BlockFailureResolver(
                            lookup,
                            failures
                    );

            HeaderChainState headerState =
                    new HeaderChainStateLoader(
                            indexes,
                            tips
                    )
                            .load()
                            .orElseThrow();

            /*
             * Direct FAILED marker пережил restart.
             */
            assertTrue(
                    failures.isFailed(
                            failedHash
                    )
            );

            /*
             * Потомок также остаётся logically failed,
             * хотя отдельный marker ему не требуется.
             */
            assertTrue(
                    resolver.isFailed(
                            require(
                                    lookup,
                                    failedDescendantHash
                            )
                    )
            );

            /*
             * Persistent best header после restart
             * остаётся на valid branch.
             */
            assertEquals(
                    validTipHash,
                    headerState
                            .bestHeaderTip()
                            .hash()
            );

            var headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexes,
                            tips
                    );

            var headerProcessor =
                    new HeaderProcessor(
                            lookup,
                            REGTEST,
                            TEST_TIME
                    );

            var batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerState,
                            headerStorage,
                            resolver
                    );

            /*
             * Peer присылает ещё один header поверх
             * failed branch.
             *
             * Header разрешено сохранить как известный,
             * но он НЕ имеет права стать best header.
             */
            BlockIndex failedTip =
                    require(
                            lookup,
                            failedDescendantHash
                    );

            BlockHeader moreFailed =
                    validHeader(
                            failedTip.hash(),
                            failedTip.header()
                                    .timestamp()
                                    .value() + 1,
                            0x31
                    );

            List<BlockIndex> storedFailedDescendant =
                    batchProcessor.process(
                            List.of(
                                    moreFailed
                            )
                    );

            assertEquals(
                    1,
                    storedFailedDescendant.size()
            );

            assertTrue(
                    indexes.find(
                            moreFailed.hash()
                    ).isPresent()
            );

            assertTrue(
                    resolver.isFailed(
                            storedFailedDescendant
                                    .getFirst()
                    )
            );

            assertEquals(
                    validTipHash,
                    tips.loadBestHeaderTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    validTipHash,
                    headerState
                            .bestHeaderTip()
                            .hash()
            );

            /*
             * Теперь приходит НОВАЯ валидная ветка.
             *
             * Она должна иметь возможность стать best,
             * несмотря на существование старого FAILED.
             */
            BlockIndex validTip =
                    require(
                            lookup,
                            validTipHash
                    );

            BlockHeader valid3 =
                    validHeader(
                            validTip.hash(),
                            validTip.header()
                                    .timestamp()
                                    .value() + 1,
                            0x41
                    );

            BlockHeader valid4 =
                    validHeader(
                            valid3.hash(),
                            valid3.timestamp()
                                    .value() + 1,
                            0x42
                    );

            List<BlockIndex> newValid =
                    batchProcessor.process(
                            List.of(
                                    valid3,
                                    valid4
                            )
                    );

            BlockIndex newBest =
                    newValid.getLast();

            assertFalse(
                    resolver.isFailed(
                            newBest
                    )
            );

            assertEquals(
                    newBest.hash(),
                    tips.loadBestHeaderTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    newBest.hash(),
                    headerState
                            .bestHeaderTip()
                            .hash()
            );
        }
    }

    @Test
    void persistentHeaderStateRefreshesAfterFailureManagerRollsBackBestTip() {

        Path path =
                tempDirectory.resolve(
                        "runtime-refresh"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(path)) {

            var indexes =
                    new RocksDbBlockIndexStore(
                            database
                    );

            var tips =
                    new RocksDbChainStateStore(
                            database
                    );

            var failures =
                    new RocksDbBlockFailureStore(
                            database
                    );

            var lookup =
                    new StoredBlockIndexLookup(
                            indexes
                    );

            var resolver =
                    new BlockFailureResolver(
                            lookup,
                            failures
                    );

            BlockIndex genesis =
                    BlockIndexFactory.createGenesis(
                            GenesisBlockFactory
                                    .create(REGTEST)
                                    .header()
                    );

            BlockIndex valid1 =
                    child(
                            genesis,
                            1_700_000_001L,
                            0x51
                    );

            BlockIndex failed1 =
                    child(
                            genesis,
                            1_700_000_002L,
                            0x61
                    );

            BlockIndex failed2 =
                    child(
                            failed1,
                            1_700_000_003L,
                            0x62
                    );

            save(
                    indexes,
                    genesis,
                    valid1,
                    failed1,
                    failed2
            );

            tips.saveBestHeaderTipHash(
                    failed2.hash()
            );

            /*
             * Это имитирует HeaderChainState,
             * которым уже пользуется sync subsystem.
             */
            HeaderChainState runtimeState =
                    new HeaderChainStateLoader(
                            indexes,
                            tips
                    )
                            .load()
                            .orElseThrow();

            assertEquals(
                    failed2.hash(),
                    runtimeState
                            .bestHeaderTip()
                            .hash()
            );

            var manager =
                    new BlockFailureManager(
                            database,
                            failures,
                            indexes,
                            tips,
                            resolver
                    );

            /*
             * Другой subsystem обнаруживает invalid block.
             */
            manager.markFailed(
                    failed1.hash()
            );

            assertEquals(
                    valid1.hash(),
                    tips.loadBestHeaderTipHash()
                            .orElseThrow()
            );

            /*
             * Ключевая проверка:
             *
             * старый RAM HeaderChainState обязан увидеть
             * persistent rollback БЕЗ restart процесса.
             */
            assertEquals(
                    valid1.hash(),
                    runtimeState
                            .bestHeaderTip()
                            .hash()
            );
        }
    }

    private static BlockIndex child(
            BlockIndex parent,
            long timestamp,
            int tag
    ) {

        return BlockIndexFactory.createChild(
                parent,
                validHeader(
                        parent.hash(),
                        timestamp,
                        tag
                )
        );
    }

    private static BlockHeader validHeader(
            Hash256 previous,
            long timestamp,
            int tag
    ) {

        Hash256 merkle =
                Hash256.fromDisplayHex(
                        String.format(
                                "%02x",
                                tag & 0xff
                        ).repeat(32)
                );

        for (long nonce = 0;
             nonce <= UInt32.MAX_VALUE;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            previous,
                            merkle,
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

        throw new AssertionError(
                "Could not construct regtest header"
        );
    }

    private static void save(
            RocksDbBlockIndexStore store,
            BlockIndex... indexes
    ) {

        for (BlockIndex index : indexes) {

            store.save(
                    BlockIndexStorageMapper.toStored(
                            index
                    )
            );
        }
    }

    private static BlockIndex require(
            BlockIndexLookup lookup,
            Hash256 hash
    ) {

        BlockIndex index =
                lookup.find(
                        hash
                );

        assertNotNull(
                index,
                "Missing BlockIndex "
                        + hash.toDisplayHex()
        );

        return index;
    }
}
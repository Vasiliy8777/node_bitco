package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.HeaderSyncCoordinator;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.storage.KnownHeaderStorage;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.PeerState;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitcoinCoreHeaderSyncIntegrationTest {

    private static final String CORE_HOST =
            "127.0.0.1";

    private static final int CORE_P2P_PORT =
            18444;

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final Hash256 REGTEST_GENESIS =
            Hash256.fromDisplayHex(
                    "0f9188f13cb7b2c71f2a335e3a4fc328"
                            + "bf5beb436012afca590b1a11466e2206"
            );

    private static final Hash256 EXPECTED_TIP =
            Hash256.fromDisplayHex(
                    "778f75bdbca77aff13c33c0f7a5a7987"
                            + "d8aa2a707ddd14099f4d7cbda8360638"
            );

    private static final long EXPECTED_HEIGHT =
            2005L;

    @TempDir
    Path tempDirectory;

    @Test
    @EnabledIfSystemProperty(
            named = "bitcoin.core.integration",
            matches = "true"
    )
    void shouldDownloadValidateAndPersistHeadersFromBitcoinCore()
            throws Exception {

        Path databasePath =
                tempDirectory.resolve(
                        "bitcoin-core-header-sync"
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            /*
             * Initialize a real regtest chain.
             *
             * This stores the actual regtest genesis
             * block/index and makes genesis the active tip.
             */
            AdjustedTime adjustedTime =
                    () -> System.currentTimeMillis()
                            / 1000L;

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            REGTEST,
                            adjustedTime,
                            new Mempool()
                    );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            assertEquals(
                    REGTEST_GENESIS,
                    validationService
                            .activeTip()
                            .hash()
            );

            assertEquals(
                    0L,
                    validationService
                            .activeTip()
                            .height()
            );

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            StoredBlockIndexLookup indexLookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            indexLookup,
                            REGTEST,
                            adjustedTime
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            HeaderChainState headerChainState =
                    new HeaderChainStateLoader(
                            indexStore,
                            chainStateStore
                    )
                            .load()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Best header state was not initialized"
                                            )
                            );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexStore,
                            chainStateStore
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
                    );

            BlockLocatorBuilder blockLocatorBuilder =
                    new BlockLocatorBuilder(
                            indexLookup
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
                                 5_000,
                                 10_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 Math.toIntExact(
                                         validationService
                                                 .activeTip()
                                                 .height()
                                 ),
                                 true
                         )) {

                peer.connect(
                        CORE_HOST,
                        CORE_P2P_PORT
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                peer
                        );

                HeaderSyncCoordinator headerSyncCoordinator =
                        new HeaderSyncCoordinator(
                                headerSynchronizer,
                                headerSyncService,
                                headerChainState,
                                blockLocatorBuilder
                        );

                /*
                 * Synchronize the complete header chain.
                 *
                 * The coordinator rebuilds the block locator
                 * from its per-session synchronization cursor
                 * after every non-empty headers response.
                 */
                Hash256 stopHash =
                        new Hash256(
                                new byte[Hash256.LENGTH]
                        );

                List<BlockIndex> processed =
                        headerSyncCoordinator.synchronize(
                                stopHash
                        );

                assertEquals(
                        EXPECTED_HEIGHT,
                        processed.size()
                );

                assertEquals(
                        1L,
                        processed.get(0).height()
                );

                assertEquals(
                        2000L,
                        processed.get(1999).height()
                );

                assertEquals(
                        EXPECTED_HEIGHT,
                        processed.get(2004).height()
                );

                assertEquals(
                        REGTEST_GENESIS,
                        processed.get(0)
                                .previousBlockHash()
                );

                /*
                 * Verify ancestry around the 2000-header
                 * protocol batch boundary.
                 */
                assertEquals(
                        processed.get(1998).hash(),
                        processed.get(1999)
                                .previousBlockHash()
                );

                assertEquals(
                        processed.get(1999).hash(),
                        processed.get(2000)
                                .previousBlockHash()
                );

                assertEquals(
                        processed.get(2003).hash(),
                        processed.get(2004)
                                .previousBlockHash()
                );

                assertEquals(
                        EXPECTED_TIP,
                        processed.get(2004).hash()
                );

                assertEquals(
                        EXPECTED_TIP,
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                assertEquals(
                        EXPECTED_TIP,
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );

                /*
                 * Verify persistence independently
                 * through the storage-backed lookup.
                 */
                BlockIndex storedTip =
                        indexLookup.find(
                                EXPECTED_TIP
                        );

                assertNotNull(
                        storedTip
                );

                assertEquals(
                        EXPECTED_HEIGHT,
                        storedTip.height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        storedTip.hash()
                );

                /*
                 * CRITICAL:
                 *
                 * Headers alone must NOT advance the
                 * fully-connected active chain.
                 *
                 * We still have block bodies only through
                 * genesis.
                 */
                assertEquals(
                        0L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        REGTEST_GENESIS,
                        validationService
                                .activeTip()
                                .hash()
                );

                System.out.println(
                        "Validated/persisted headers: "
                                + processed.size()
                );

                System.out.println(
                        "Best downloaded header: "
                                + storedTip.hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Best downloaded header height: "
                                + storedTip.height()
                );

                System.out.println(
                        "Active block-chain tip before block: "
                                + validationService
                                .activeTip()
                                .hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Active block-chain height before block: "
                                + validationService
                                .activeTip()
                                .height()
                );

                /*
                 * Header synchronization is complete.
                 *
                 * Now download the corresponding block bodies
                 * and submit every block through the real
                 * NodeValidationService / BlockProcessor pipeline.
                 */
                PeerManager peerManager =
                        new PeerManager();

                peerManager.add(
                        peer
                );

                BlockDownloadService blockDownloadService =
                        new BlockDownloadService(
                                peerManager
                        );

                BlockDownloadScheduler blockDownloadScheduler =
                        new BlockDownloadScheduler(
                                peerManager,
                                blockDownloadService,
                                new BlockDownloadTimeoutPolicy(
                                        Duration.ofMinutes(10)
                                )
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                indexLookup,
                                blockStore
                        );

                List<BlockIndex> downloadedBlocks =
                        coordinator.synchronize();

                assertEquals(
                        EXPECTED_HEIGHT,
                        downloadedBlocks.size()
                );

                assertEquals(
                        1L,
                        downloadedBlocks.get(0)
                                .height()
                );

                assertEquals(
                        2000L,
                        downloadedBlocks.get(1999)
                                .height()
                );

                assertEquals(
                        EXPECTED_HEIGHT,
                        downloadedBlocks.get(2004)
                                .height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        downloadedBlocks.get(2004)
                                .hash()
                );

                /*
                 * The fully validated block chain must now
                 * have caught up with the best header chain.
                 */
                BlockIndex activeTip =
                        validationService.activeTip();

                assertEquals(
                        EXPECTED_HEIGHT,
                        activeTip.height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        activeTip.hash()
                );

                assertEquals(
                        headerChainState
                                .bestHeaderTip()
                                .hash(),
                        activeTip.hash()
                );

                /*
                 * Verify that the active-chain tip was
                 * persisted, not only changed in memory.
                 */
                assertEquals(
                        EXPECTED_TIP,
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertEquals(
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                System.out.println(
                        "Downloaded/validated blocks: "
                                + downloadedBlocks.size()
                );

                System.out.println(
                        "Active block-chain tip after block IBD: "
                                + activeTip.hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Active block-chain height after block IBD: "
                                + activeTip.height()
                );
            }
        }
    }
    @Test
    @EnabledIfSystemProperty(
            named = "bitcoin.core.integration",
            matches = "true"
    )
    void shouldResumeBlockIbdAfterRestartWithoutRedownloadingConnectedBlocks()
            throws Exception {

        Path databasePath =
                tempDirectory.resolve(
                        "bitcoin-core-block-resume"
                );

        AdjustedTime adjustedTime =
                () -> System.currentTimeMillis()
                        / 1000L;

        Hash256 block1000Hash;

        /*
         * =========================================================
         * SESSION 1
         *
         * 1. Start from genesis.
         * 2. Download all 2005 headers.
         * 3. Download only block bodies #1..#1000.
         * 4. Close peer and RocksDB.
         * =========================================================
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            REGTEST,
                            adjustedTime,
                            new Mempool()
                    );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            StoredBlockIndexLookup indexLookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            HeaderChainState headerChainState =
                    new HeaderChainStateLoader(
                            indexStore,
                            chainStateStore
                    )
                            .load()
                            .orElseThrow();

            HeaderProcessor headerProcessor =
                    new HeaderProcessor(
                            indexLookup,
                            REGTEST,
                            adjustedTime
                    );

            KnownHeaderStorage headerStorage =
                    new KnownHeaderStorage(
                            database,
                            indexStore,
                            chainStateStore
                    );

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            headerChainState,
                            headerStorage
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
                    );

            BlockLocatorBuilder blockLocatorBuilder =
                    new BlockLocatorBuilder(
                            indexLookup
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
                                 5_000,
                                 10_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        CORE_HOST,
                        CORE_P2P_PORT
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                peer
                        );

                HeaderSyncCoordinator headerSyncCoordinator =
                        new HeaderSyncCoordinator(
                                headerSynchronizer,
                                headerSyncService,
                                headerChainState,
                                blockLocatorBuilder
                        );

                Hash256 stopHash =
                        new Hash256(
                                new byte[Hash256.LENGTH]
                        );

                List<BlockIndex> headers =
                        headerSyncCoordinator.synchronize(
                                stopHash
                        );

                assertEquals(
                        EXPECTED_HEIGHT,
                        headers.size()
                );

                assertEquals(
                        EXPECTED_TIP,
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                /*
                 * Header chain is #2005,
                 * but active block chain is still genesis.
                 */
                assertEquals(
                        0L,
                        validationService
                                .activeTip()
                                .height()
                );

                PeerManager peerManager =
                        new PeerManager();

                peerManager.add(
                        peer
                );

                BlockDownloadService blockDownloadService =
                        new BlockDownloadService(
                                peerManager
                        );

                BlockDownloadScheduler blockDownloadScheduler =
                        new BlockDownloadScheduler(
                                peerManager,
                                blockDownloadService,
                                new BlockDownloadTimeoutPolicy(
                                        Duration.ofMinutes(10)
                                )
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                indexLookup,
                                blockStore
                        );

                /*
                 * Deliberately stop body synchronization
                 * at block #1000.
                 */
                List<BlockIndex> firstBatch =
                        coordinator.synchronize(
                                1000
                        );

                assertEquals(
                        1000,
                        firstBatch.size()
                );

                assertEquals(
                        1L,
                        firstBatch.get(0)
                                .height()
                );

                assertEquals(
                        1000L,
                        firstBatch.get(999)
                                .height()
                );

                BlockIndex activeTip =
                        validationService.activeTip();

                assertEquals(
                        1000L,
                        activeTip.height()
                );

                block1000Hash =
                        activeTip.hash();

                assertEquals(
                        firstBatch.get(999).hash(),
                        block1000Hash
                );

                /*
                 * The two persisted tips must now intentionally
                 * be different.
                 */
                assertEquals(
                        block1000Hash,
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertEquals(
                        EXPECTED_TIP,
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );

                System.out.println(
                        "Session 1 active block height: "
                                + activeTip.height()
                );

                System.out.println(
                        "Session 1 active block hash: "
                                + activeTip.hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Session 1 best header height: "
                                + headerChainState
                                .bestHeaderTip()
                                .height()
                );
            }
        }

        /*
         * At this point BOTH the Peer and RocksDbDatabase
         * from session 1 have actually been closed.
         *
         * Everything below is reconstructed from persisted state.
         */

        /*
         * =========================================================
         * SESSION 2
         *
         * Open the same database from scratch.
         * =========================================================
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            REGTEST,
                            adjustedTime,
                            new Mempool()
                    );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            /*
             * ChainInitializer inside NodeValidationService
             * must restore activeTip #1000.
             */
            BlockIndex restoredActiveTip =
                    validationService.activeTip();

            assertEquals(
                    1000L,
                    restoredActiveTip.height()
            );

            assertEquals(
                    block1000Hash,
                    restoredActiveTip.hash()
            );

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            StoredBlockIndexLookup indexLookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            /*
             * Header state is reconstructed independently
             * from the persisted best-header pointer.
             */
            HeaderChainState headerChainState =
                    new HeaderChainStateLoader(
                            indexStore,
                            chainStateStore
                    )
                            .load()
                            .orElseThrow();

            assertEquals(
                    EXPECTED_HEIGHT,
                    headerChainState
                            .bestHeaderTip()
                            .height()
            );

            assertEquals(
                    EXPECTED_TIP,
                    headerChainState
                            .bestHeaderTip()
                            .hash()
            );

            /*
             * Persistence itself must still show:
             *
             * activeTip     = #1000
             * bestHeaderTip = #2005
             */
            assertEquals(
                    block1000Hash,
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    EXPECTED_TIP,
                    chainStateStore
                            .loadBestHeaderTipHash()
                            .orElseThrow()
            );

            try (PeerConnection connection =
                         new PeerConnection(
                                 REGTEST,
                                 5_000,
                                 10_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 Math.toIntExact(
                                         restoredActiveTip.height()
                                 ),
                                 true
                         )) {

                peer.connect(
                        CORE_HOST,
                        CORE_P2P_PORT
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                PeerManager peerManager =
                        new PeerManager();

                peerManager.add(
                        peer
                );

                BlockDownloadService blockDownloadService =
                        new BlockDownloadService(
                                peerManager
                        );

                BlockDownloadScheduler blockDownloadScheduler =
                        new BlockDownloadScheduler(
                                peerManager,
                                blockDownloadService,
                                new BlockDownloadTimeoutPolicy(
                                        Duration.ofMinutes(10)
                                )
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                indexLookup,
                                blockStore
                        );

                /*
                 * IMPORTANT:
                 *
                 * synchronize() replans from restored
                 * activeTip #1000, not genesis.
                 */
                List<BlockIndex> resumed =
                        coordinator.synchronize();

                /*
                 * 2005 - 1000 = 1005.
                 *
                 * If blocks #1..#1000 were downloaded again,
                 * this assertion would fail.
                 */
                assertEquals(
                        1005,
                        resumed.size()
                );

                /*
                 * The first requested block after restart
                 * must therefore be #1001.
                 */
                assertEquals(
                        1001L,
                        resumed.get(0)
                                .height()
                );

                assertEquals(
                        2005L,
                        resumed.get(1004)
                                .height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        resumed.get(1004)
                                .hash()
                );

                BlockIndex finalActiveTip =
                        validationService.activeTip();

                assertEquals(
                        EXPECTED_HEIGHT,
                        finalActiveTip.height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        finalActiveTip.hash()
                );

                assertEquals(
                        headerChainState
                                .bestHeaderTip()
                                .hash(),
                        finalActiveTip.hash()
                );

                /*
                 * Both persisted pointers must converge
                 * after resume completes.
                 */
                assertEquals(
                        EXPECTED_TIP,
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertEquals(
                        EXPECTED_TIP,
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );

                System.out.println(
                        "Restart restored active height: "
                                + restoredActiveTip.height()
                );

                System.out.println(
                        "Restart restored best-header height: "
                                + headerChainState
                                .bestHeaderTip()
                                .height()
                );

                System.out.println(
                        "Blocks downloaded after restart: "
                                + resumed.size()
                );

                System.out.println(
                        "First resumed block height: "
                                + resumed.get(0)
                                .height()
                );

                System.out.println(
                        "Final active block height: "
                                + finalActiveTip.height()
                );

                System.out.println(
                        "Final active block hash: "
                                + finalActiveTip.hash()
                                .toDisplayHex()
                );
            }
        }
    }
}
package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BlockSyncCoordinatorTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    private static final long TIME =
            1_700_000_000L;

    private static final long BITS =
            0x207fffffL;

    private static final long REWARD =
            5_000_000_000L;

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    @TempDir
    Path directory;

    @Test
    void shouldDownloadAndConnectLinearBestHeaderChain()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        1
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        2
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        3
                );

        BlockIndex index3 =
                BlockIndexFactory.createChild(
                        index2,
                        block3.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-coordinator"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            /*
             * Initialize the real validation service first.
             * This creates/persists the actual regtest genesis.
             */
            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
                            new Mempool()
                    );
            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            assertEquals(
                    genesis.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            /*
             * Header sync has already happened:
             * bodies are absent, but indexes 1..3 are known.
             */
            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index3
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index3.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index3
                    );

            CountDownLatch releaseServer =
                    new CountDownLatch(
                            1
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    List.of(
                                            block1,
                                            block2,
                                            block3
                                    ),
                                    releaseServer
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
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
                                lookup,
                                blockStore
                        );

                List<BlockIndex> downloaded =
                        coordinator.synchronize();

                assertEquals(
                        3,
                        downloaded.size()
                );

                assertEquals(
                        block1.hash(),
                        downloaded.get(0).hash()
                );

                assertEquals(
                        block2.hash(),
                        downloaded.get(1).hash()
                );

                assertEquals(
                        block3.hash(),
                        downloaded.get(2).hash()
                );

                assertEquals(
                        block3.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        3L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block3.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertTrue(
                        peer.isReady()
                );

                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldDownloadCompetingBranchAndReorganizeToBestHeaderChain()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        /*
         * Active branch:
         *
         * genesis -> A1 -> A2 -> A3
         */
        Block a1 =
                child(
                        genesis,
                        11
                );

        BlockIndex a1Index =
                BlockIndexFactory.createChild(
                        genesis,
                        a1.header()
                );

        Block a2 =
                child(
                        a1Index,
                        12
                );

        BlockIndex a2Index =
                BlockIndexFactory.createChild(
                        a1Index,
                        a2.header()
                );

        Block a3 =
                child(
                        a2Index,
                        13
                );

        /*
         * Competing best-header branch:
         *
         * genesis -> B1 -> B2 -> B3 -> B4
         */
        Block b1 =
                child(
                        genesis,
                        21
                );

        BlockIndex b1Index =
                BlockIndexFactory.createChild(
                        genesis,
                        b1.header()
                );

        Block b2 =
                child(
                        b1Index,
                        22
                );

        BlockIndex b2Index =
                BlockIndexFactory.createChild(
                        b1Index,
                        b2.header()
                );

        Block b3 =
                child(
                        b2Index,
                        23
                );

        BlockIndex b3Index =
                BlockIndexFactory.createChild(
                        b2Index,
                        b3.header()
                );

        Block b4 =
                child(
                        b3Index,
                        24
                );

        BlockIndex b4Index =
                BlockIndexFactory.createChild(
                        b3Index,
                        b4.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-reorg"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
                            new Mempool()
                    );
            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            /*
             * First establish the real active A-chain.
             */
            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a1
                    )
            );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a2
                    )
            );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a3
                    )
            );

            assertEquals(
                    a3.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            assertEquals(
                    3L,
                    validationService
                            .activeTip()
                            .height()
            );

            /*
             * Simulate completed header synchronization.
             * B1..B4 indexes are known, but their bodies
             * have not been downloaded yet.
             */
            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b1Index
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b2Index
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b3Index
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b4Index
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    b4.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            b4Index
                    );

            CountDownLatch releaseServer =
                    new CountDownLatch(
                            1
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    List.of(
                                            b1,
                                            b2,
                                            b3,
                                            b4
                                    ),
                                    releaseServer
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
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
                                        Duration.ofMinutes(10))
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore
                        );

                List<BlockIndex> downloaded =
                        coordinator.synchronize();

                /*
                 * The download plan must contain only
                 * the competing B branch.
                 *
                 * A1..A3 must not be requested again.
                 */
                assertEquals(
                        4,
                        downloaded.size()
                );

                assertEquals(
                        b1.hash(),
                        downloaded.get(0).hash()
                );

                assertEquals(
                        b2.hash(),
                        downloaded.get(1).hash()
                );

                assertEquals(
                        b3.hash(),
                        downloaded.get(2).hash()
                );

                assertEquals(
                        b4.hash(),
                        downloaded.get(3).hash()
                );

                /*
                 * B4 has more cumulative work than A3,
                 * therefore processing the final B block
                 * must cause the real chain reorganization.
                 */
                assertEquals(
                        b4.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        4L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        b4.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                /*
                 * Header tip itself does not change:
                 * it was already B4 before bodies arrived.
                 */
                assertEquals(
                        b4.hash(),
                        headerChainState
                                .bestHeaderTip()
                                .hash()
                );

                assertTrue(
                        peer.isReady()
                );

                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldStopAtConfiguredBlockLimitAndResume()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        31
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        32
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        33
                );

        BlockIndex index3 =
                BlockIndexFactory.createChild(
                        index2,
                        block3.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-bounded"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
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

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index3
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index3.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index3
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    List.of(
                                            block1,
                                            block2,
                                            block3
                                    )
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
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
                                        Duration.ofMinutes(10))
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore
                        );

                /*
                 * First bounded pass:
                 * only block #1 may be downloaded.
                 */
                List<BlockIndex> first =
                        coordinator.synchronize(
                                1
                        );

                assertEquals(
                        1,
                        first.size()
                );

                assertEquals(
                        index1.hash(),
                        first.get(0).hash()
                );

                assertEquals(
                        index1.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        1L,
                        validationService
                                .activeTip()
                                .height()
                );

                /*
                 * The persisted best-header tip remains #3.
                 */
                assertEquals(
                        index3.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );

                /*
                 * Second invocation replans from the NEW
                 * active tip and therefore starts at #2,
                 * not #1.
                 */
                List<BlockIndex> second =
                        coordinator.synchronize();

                assertEquals(
                        2,
                        second.size()
                );

                assertEquals(
                        index2.hash(),
                        second.get(0).hash()
                );

                assertEquals(
                        index3.hash(),
                        second.get(1).hash()
                );

                assertEquals(
                        index3.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        3L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        index3.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldResumeCompetingBranchFromPersistedBodiesAfterRestart()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        /*
         * Active branch:
         *
         * genesis -> A1 -> A2 -> A3
         */
        Block a1 =
                child(
                        genesis,
                        41
                );

        BlockIndex a1Index =
                BlockIndexFactory.createChild(
                        genesis,
                        a1.header()
                );

        Block a2 =
                child(
                        a1Index,
                        42
                );

        BlockIndex a2Index =
                BlockIndexFactory.createChild(
                        a1Index,
                        a2.header()
                );

        Block a3 =
                child(
                        a2Index,
                        43
                );

        /*
         * Competing branch:
         *
         * genesis -> B1 -> B2 -> B3 -> B4
         */
        Block b1 =
                child(
                        genesis,
                        51
                );

        BlockIndex b1Index =
                BlockIndexFactory.createChild(
                        genesis,
                        b1.header()
                );

        Block b2 =
                child(
                        b1Index,
                        52
                );

        BlockIndex b2Index =
                BlockIndexFactory.createChild(
                        b1Index,
                        b2.header()
                );

        Block b3 =
                child(
                        b2Index,
                        53
                );

        BlockIndex b3Index =
                BlockIndexFactory.createChild(
                        b2Index,
                        b3.header()
                );

        Block b4 =
                child(
                        b3Index,
                        54
                );

        BlockIndex b4Index =
                BlockIndexFactory.createChild(
                        b3Index,
                        b4.header()
                );

        Path databasePath =
                directory.resolve(
                        "block-sync-side-branch-restart"
                );

        /*
         * =====================================================
         * SESSION 1
         *
         * Establish A3 as active chain.
         * Persist B1 and B2 bodies.
         * B branch is not strong enough yet to replace A3.
         * =====================================================
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     )) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
                            new Mempool()
                    );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a1
                    )
            );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a2
                    )
            );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED,
                    validationService.processBlock(
                            a3
                    )
            );

            assertEquals(
                    a3.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            /*
             * Processing B1/B2 stores their bodies/indexes,
             * but A3 remains the active tip.
             */
            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult
                            .STORED_SIDE_CHAIN_CONTEXT_PENDING,
                    validationService.processBlock(
                            b1
                    )
            );

            assertEquals(
                    ru.bitcoin.node.chain.BlockProcessingResult
                            .STORED_SIDE_CHAIN_CONTEXT_PENDING,
                    validationService.processBlock(
                            b2
                    )
            );

            assertEquals(
                    a3.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            /*
             * Header synchronization already knows B3/B4.
             */
            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b3Index
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            b4Index
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    b4.hash()
            );

            /*
             * Verify the state we intentionally persist.
             */
            assertEquals(
                    a3.hash(),
                    chainStateStore
                            .loadActiveTipHash()
                            .orElseThrow()
            );

            assertEquals(
                    b4.hash(),
                    chainStateStore
                            .loadBestHeaderTipHash()
                            .orElseThrow()
            );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            assertTrue(
                    blockStore.find(
                            b1.hash()
                    ).isPresent()
            );

            assertTrue(
                    blockStore.find(
                            b2.hash()
                    ).isPresent()
            );

            assertTrue(
                    blockStore.find(
                            b3.hash()
                    ).isEmpty()
            );

            assertTrue(
                    blockStore.find(
                            b4.hash()
                    ).isEmpty()
            );
        }

        /*
         * =====================================================
         * SESSION 2
         *
         * Reopen the same database.
         *
         * B1/B2 must come from RocksDB.
         * Peer provides ONLY B3/B4.
         * =====================================================
         */
        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             databasePath
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
                            new Mempool()
                    );

            assertEquals(
                    a3.hash(),
                    validationService
                            .activeTip()
                            .hash()
            );

            assertEquals(
                    3L,
                    validationService
                            .activeTip()
                            .height()
            );

            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            BlockIndexLookup lookup =
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

            assertEquals(
                    b4.hash(),
                    headerChainState
                            .bestHeaderTip()
                            .hash()
            );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            /*
             * The fake peer deliberately has ONLY B3/B4.
             *
             * runPeer() asserts the exact requested hash.
             * Therefore a network request for B1 or B2
             * makes this test fail.
             */
            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    List.of(
                                            b3,
                                            b4
                                    )
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
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
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
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
                                        Duration.ofMinutes(10))
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize();

                /*
                 * IMPORTANT:
                 *
                 * The connect path still contains all four
                 * B blocks. B1/B2 are processed locally;
                 * B3/B4 are downloaded.
                 */
                assertEquals(
                        4,
                        processed.size()
                );

                assertEquals(
                        b1.hash(),
                        processed.get(0).hash()
                );

                assertEquals(
                        b2.hash(),
                        processed.get(1).hash()
                );

                assertEquals(
                        b3.hash(),
                        processed.get(2).hash()
                );

                assertEquals(
                        b4.hash(),
                        processed.get(3).hash()
                );

                /*
                 * Processing B4 makes the B branch stronger
                 * and causes the real reorganization.
                 */
                assertEquals(
                        b4.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        4L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        b4.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertEquals(
                        b4.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailOverToSecondPeerAndConnectDownloadedBlock()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        61
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-multi-peer-failover"
                             )
                     );

             ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
                            new Mempool()
                    );

            RocksDbBlockStore blockStore =
                    new RocksDbBlockStore(
                            database
                    );

            /*
             * Header synchronization already knows block #1,
             * but its body is deliberately absent.
             */
            RocksDbBlockIndexStore indexStore =
                    new RocksDbBlockIndexStore(
                            database
                    );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index1.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index1
                    );

            assertTrue(
                    blockStore.find(
                            block1.hash()
                    ).isEmpty()
            );

            CompletableFuture<Void> firstServerFuture =
                    CompletableFuture.runAsync(
                            () -> runNotFoundPeer(
                                    firstServer,
                                    block1.hash(),
                                    0x2122232425262728L
                            )
                    );

            CompletableFuture<Void> secondServerFuture =
                    CompletableFuture.runAsync(
                            () -> runBlockPeer(
                                    secondServer,
                                    block1,
                                    block1.hash(),
                                    0x3132333435363738L
                            )
                    );

            try (PeerManager peerManager =
                         new PeerManager()) {

                Peer firstPeer =
                        connectPeer(
                                firstServer.getLocalPort()
                        );

                Peer secondPeer =
                        connectPeer(
                                secondServer.getLocalPort()
                        );

                peerManager.add(
                        firstPeer
                );

                peerManager.add(
                        secondPeer
                );

                assertEquals(
                        2,
                        peerManager.readyPeers()
                                .size()
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
                                        Duration.ofMinutes(10))
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize();

                assertEquals(
                        1,
                        processed.size()
                );

                assertEquals(
                        index1.hash(),
                        processed.get(0).hash()
                );

                /*
                 * The block returned by the second peer must have
                 * passed through the real validation/connection
                 * pipeline.
                 */
                assertEquals(
                        index1.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        1L,
                        validationService
                                .activeTip()
                                .height()
                );

                /*
                 * KnownBlockStorage inside BlockProcessor must
                 * have persisted the downloaded body.
                 */
                assertTrue(
                        blockStore.find(
                                block1.hash()
                        ).isPresent()
                );

                assertEquals(
                        index1.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                assertEquals(
                        index1.hash(),
                        chainStateStore
                                .loadBestHeaderTipHash()
                                .orElseThrow()
                );
            }

            firstServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            secondServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldAssignAdditionalBlockWhileEarlierRequestsRemainInFlight()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        81
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        82
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        83
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch block3Requested =
                    new CountDownLatch(
                            1
                    );

            CountDownLatch releaseHealthyPeers =
                    new CountDownLatch(
                            1
                    );

            CompletableFuture<Void> firstServerFuture =
                    CompletableFuture.runAsync(
                            () -> runFastWorkConservingPeer(
                                    firstServer,
                                    block1,
                                    block3,
                                    0x6162636465666768L,
                                    block3Requested,
                                    releaseHealthyPeers
                            )
                    );

            CompletableFuture<Void> secondServerFuture =
                    CompletableFuture.runAsync(
                            () -> runSlowWorkConservingPeer(
                                    secondServer,
                                    block2,
                                    0x7172737475767778L,
                                    block3Requested,
                                    releaseHealthyPeers
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            BlockDownloadService blockDownloadService =
                    new BlockDownloadService(
                            peerManager
                    );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            blockDownloadService,
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMinutes(10))
                    );

            List<Block> downloaded =
                    scheduler.download(
                            List.of(
                                    block1.hash(),
                                    block2.hash(),
                                    block3.hash()
                            )
                    );

            assertEquals(
                    3,
                    downloaded.size()
            );

            /*
             * Completion order is irrelevant.
             * Public result must remain chain/request order.
             */
            assertEquals(
                    block1.hash(),
                    downloaded.get(0).hash()
            );

            assertEquals(
                    block2.hash(),
                    downloaded.get(1).hash()
            );

            assertEquals(
                    block3.hash(),
                    downloaded.get(2).hash()
            );

            assertTrue(
                    firstPeer.isReady()
            );

            assertTrue(
                    secondPeer.isReady()
            );

            releaseHealthyPeers.countDown();

            firstServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            secondServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldReassignBlockAfterPeerTransportFailureAndContinueDownloading()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        91
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        92
                );

        try (ServerSocket failingServer =
                     new ServerSocket(0);

             ServerSocket healthyServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch failingPeerRequestedBlock =
                    new CountDownLatch(1);

            CountDownLatch releaseHealthyPeer =
                    new CountDownLatch(1);

            CompletableFuture<Void> failingServerFuture =
                    CompletableFuture.runAsync(
                            () -> runDisconnectingBlockPeer(
                                    failingServer,
                                    block1.hash(),
                                    0x1112131415161718L,
                                    failingPeerRequestedBlock
                            )
                    );

            CompletableFuture<Void> healthyServerFuture =
                    CompletableFuture.runAsync(
                            () -> runFailoverHealthyPeer(
                                    healthyServer,
                                    block2,
                                    block1,
                                    0x2122232425262728L,
                                    failingPeerRequestedBlock,
                                    releaseHealthyPeer
                            )
                    );

            Peer failingPeer =
                    connectPeer(
                            failingServer.getLocalPort()
                    );

            Peer healthyPeer =
                    connectPeer(
                            healthyServer.getLocalPort()
                    );

            peerManager.add(
                    failingPeer
            );

            peerManager.add(
                    healthyPeer
            );

            BlockDownloadService blockDownloadService =
                    new BlockDownloadService(
                            peerManager
                    );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            blockDownloadService,
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMinutes(10))
                    );

            List<Block> downloaded =
                    scheduler.download(
                            List.of(
                                    block1.hash(),
                                    block2.hash()
                            )
                    );

            assertEquals(
                    2,
                    downloaded.size()
            );

            /*
             * Even though B1 had to be reassigned,
             * public ordering must remain unchanged.
             */
            assertEquals(
                    block1.hash(),
                    downloaded.get(0).hash()
            );

            assertEquals(
                    block2.hash(),
                    downloaded.get(1).hash()
            );

            /*
             * Transport failure must close the broken peer.
             */
            assertFalse(
                    failingPeer.isReady()
            );

            /*
             * The surviving peer remains usable.
             */
            assertTrue(
                    healthyPeer.isReady()
            );

            releaseHealthyPeer.countDown();

            failingServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            healthyServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailAfterAllPeersReportBlockNotFound()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block =
                child(
                        genesis,
                        101
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch releaseNotFoundPeers =
                    new CountDownLatch(1);

            CompletableFuture<Void> firstServerFuture =
                    CompletableFuture.runAsync(
                            () -> runNotFoundBlockPeer(
                                    firstServer,
                                    block.hash(),
                                    0x3132333435363738L,
                                    releaseNotFoundPeers
                            )
                    );

            CompletableFuture<Void> secondServerFuture =
                    CompletableFuture.runAsync(
                            () -> runNotFoundBlockPeer(
                                    secondServer,
                                    block.hash(),
                                    0x4142434445464748L,
                                    releaseNotFoundPeers
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            BlockDownloadService blockDownloadService =
                    new BlockDownloadService(
                            peerManager
                    );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            blockDownloadService,
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMinutes(10))
                    );

            IOException exception =
                    assertThrows(
                            IOException.class,
                            () -> scheduler.download(
                                    List.of(
                                            block.hash()
                                    )
                            )
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    block.hash()
                                            .toDisplayHex()
                            )
            );

            /*
             * The block was attempted against both peers.
             * Both failures must be preserved for diagnostics.
             */
            assertEquals(
                    2,
                    exception.getSuppressed().length
            );

            assertInstanceOf(
                    BlockNotFoundException.class,
                    exception.getSuppressed()[0]
            );

            assertInstanceOf(
                    BlockNotFoundException.class,
                    exception.getSuppressed()[1]
            );

            /*
             * NOTFOUND is a valid protocol response.
             * Neither peer should be disconnected.
             */
            assertTrue(
                    firstPeer.isReady()
            );

            assertTrue(
                    secondPeer.isReady()
            );

            releaseNotFoundPeers.countDown();

            firstServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            secondServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldSlideDownloadWindowForwardAfterProcessingEarlierBlock()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        111
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        112
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        113
                );

        BlockIndex index3 =
                BlockIndexFactory.createChild(
                        index2,
                        block3.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-sliding-window"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
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

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index3
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index3.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index3
                    );

            /*
             * Server proves the sliding-window property:
             *
             * 1. B1 and B2 are initially requested.
             * 2. B1 is returned.
             * 3. B2 remains deliberately outstanding.
             * 4. B3 MUST be requested before B2 is returned.
             *
             * Therefore B3 can only appear if processing B1
             * slides the download horizon forward.
             */
            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSlidingWindowPeer(
                                    serverSocket,
                                    block1,
                                    block2,
                                    block3
                            )
                    );

            try (PeerManager peerManager =
                         new PeerManager()) {

                Peer peer =
                        connectPeer(
                                serverSocket.getLocalPort()
                        );

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
                                        Duration.ofMinutes(
                                                10
                                        )
                                )
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore,
                                2
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize();

                assertEquals(
                        3,
                        processed.size()
                );

                assertEquals(
                        block1.hash(),
                        processed.get(0).hash()
                );

                assertEquals(
                        block2.hash(),
                        processed.get(1).hash()
                );

                assertEquals(
                        block3.hash(),
                        processed.get(2).hash()
                );

                assertEquals(
                        block3.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        3L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block3.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runSlidingWindowPeer(
            ServerSocket serverSocket,
            Block firstBlock,
            Block secondBlock,
            Block thirdBlock
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            Map<Hash256, Block> initialWindow =
                    Map.of(
                            firstBlock.hash(),
                            firstBlock,
                            secondBlock.hash(),
                            secondBlock
                    );

            Set<Hash256> requested =
                    new HashSet<>();

            /*
             * Initial horizon contains exactly B1 and B2.
             *
             * Their wire request order is intentionally
             * unspecified.
             */
            for (int i = 0;
                 i < 2;
                 i++) {

                BitcoinMessage getDataWire =
                        reader.read(
                                input
                        ).orElseThrow();

                assertEquals(
                        "getdata",
                        getDataWire.command()
                );

                GetDataMessage getData =
                        BitcoinMessages.decodeGetData(
                                getDataWire
                        );

                assertEquals(
                        1,
                        getData.size()
                );

                InventoryVector vector =
                        getData.inventory()
                                .get(0);

                assertEquals(
                        InventoryVector.MSG_WITNESS_BLOCK,
                        vector.type()
                );

                Hash256 requestedHash =
                        vector.hash();

                assertTrue(
                        initialWindow.containsKey(
                                requestedHash
                        ),
                        "Initial download window received unexpected request "
                                + requestedHash.toDisplayHex()
                );

                assertTrue(
                        requested.add(
                                requestedHash
                        ),
                        "Initial download window received duplicate request "
                                + requestedHash.toDisplayHex()
                );
            }

            assertEquals(
                    initialWindow.keySet(),
                    requested
            );

            /*
             * Return ONLY B1.
             *
             * B2 deliberately remains in-flight.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            firstBlock
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * Critical assertion:
             *
             * B3 must now be requested while B2 is STILL
             * outstanding.
             *
             * The old batch-barrier coordinator deadlocks here:
             * it waits for B2 before it can expose B3.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    thirdBlock.hash()
            );

            /*
             * Now complete B2.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            secondBlock
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * And deliberately complete B3 immediately after it.
             *
             * Consensus processing must nevertheless remain
             * B1 -> B2 -> B3.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            thirdBlock
                                    )
                            )
                    )
            );

            output.flush();

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runNotFoundBlockPeer(
            ServerSocket serverSocket,
            Hash256 expectedBlockHash,
            long remoteNonce,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            assertRequestedBlock(
                    reader,
                    input,
                    expectedBlockHash
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.notFound(
                                    new NotFoundMessage(
                                            List.of(
                                                    new InventoryVector(
                                                            InventoryVector.MSG_BLOCK,
                                                            expectedBlockHash
                                                    )
                                            )
                                    )
                            )
                    )
            );

            output.flush();

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release NOTFOUND peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runDisconnectingBlockPeer(
            ServerSocket serverSocket,
            Hash256 expectedBlockHash,
            long remoteNonce,
            CountDownLatch requestReceived
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            assertRequestedBlock(
                    reader,
                    input,
                    expectedBlockHash
            );

            requestReceived.countDown();

            /*
             * Return without sending block/notfound.
             *
             * Closing the socket is a transport failure,
             * not a valid NOTFOUND response.
             */

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runFailoverHealthyPeer(
            ServerSocket serverSocket,
            Block initiallyAssignedBlock,
            Block reassignedBlock,
            long remoteNonce,
            CountDownLatch failingPeerRequestedBlock,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            /*
             * Ensure the failing peer really received B1.
             */
            boolean failingPeerHasRequest =
                    failingPeerRequestedBlock.await(
                            5,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    failingPeerHasRequest,
                    "Failing peer must receive its original block request"
            );

            /*
             * Healthy peer receives its own original work: B2.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    initiallyAssignedBlock.hash()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            initiallyAssignedBlock
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * Once P1's transport failure is observed, B1 becomes
             * pending again. P2 must then receive B1.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    reassignedBlock.hash()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            reassignedBlock
                                    )
                            )
                    )
            );

            output.flush();

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release healthy failover peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runFastWorkConservingPeer(
            ServerSocket serverSocket,
            Block firstBlock,
            Block thirdBlock,
            long remoteNonce,
            CountDownLatch block3Requested,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            /*
             * With multiple in-flight requests per peer, P1 may
             * receive B3 while B1 is still outstanding.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    firstBlock.hash()
            );

            assertRequestedBlock(
                    reader,
                    input,
                    thirdBlock.hash()
            );

            /*
             * Prove that the additional request was assigned
             * before the slow peer was allowed to complete B2.
             */
            block3Requested.countDown();

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            firstBlock
                                    )
                            )
                    )
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            thirdBlock
                                    )
                            )
                    )
            );

            output.flush();

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release fast peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runSlowWorkConservingPeer(
            ServerSocket serverSocket,
            Block secondBlock,
            long remoteNonce,
            CountDownLatch block3Requested,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            /*
             * P2 receives B2.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    secondBlock.hash()
            );

            /*
             * Deliberately hold B2.
             *
             * B2 remains outstanding while P1 is allowed to
             * receive an additional request for B3. This proves
             * that one outstanding request does not consume the
             * peer's entire in-flight capacity.
             */
            boolean thirdBlockWasRequested =
                    block3Requested.await(
                            5,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    thirdBlockWasRequested,
                    "Fast peer must receive B3 before slow peer releases B2"
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            secondBlock
                                    )
                            )
                    )
            );

            output.flush();

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release slow peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    @Test
    void shouldHoldOutOfOrderCompletionUntilParentIsProcessed()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        131
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        132
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        133
                );

        BlockIndex index3 =
                BlockIndexFactory.createChild(
                        index2,
                        block3.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-out-of-order"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
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

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index3
                    )
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index3.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index3
                    );

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runOutOfOrderSlidingPeer(
                                    serverSocket,
                                    block1,
                                    block2,
                                    block3
                            )
                    );

            try (PeerManager peerManager =
                         new PeerManager()) {

                Peer peer =
                        connectPeer(
                                serverSocket.getLocalPort()
                        );

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
                                        Duration.ofMinutes(
                                                10
                                        )
                                )
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore,
                                2
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize();

                assertEquals(
                        List.of(
                                block1.hash(),
                                block2.hash(),
                                block3.hash()
                        ),
                        processed.stream()
                                .map(BlockIndex::hash)
                                .toList()
                );

                assertEquals(
                        block3.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        3L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block3.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runOutOfOrderSlidingPeer(
            ServerSocket serverSocket,
            Block firstBlock,
            Block secondBlock,
            Block thirdBlock
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            /*
             * Initial horizon = B1,B2.
             * Request order between them is intentionally irrelevant.
             */
            Set<Hash256> initialRequests =
                    new HashSet<>();

            for (int i = 0; i < 2; i++) {

                BitcoinMessage wire =
                        reader.read(
                                input
                        ).orElseThrow();

                assertEquals(
                        "getdata",
                        wire.command()
                );

                GetDataMessage getData =
                        BitcoinMessages.decodeGetData(
                                wire
                        );

                assertEquals(
                        1,
                        getData.size()
                );

                Hash256 hash =
                        getData.inventory()
                                .get(0)
                                .hash();

                assertTrue(
                        hash.equals(firstBlock.hash())
                                || hash.equals(secondBlock.hash())
                );

                assertTrue(
                        initialRequests.add(
                                hash
                        )
                );
            }

            assertEquals(
                    Set.of(
                            firstBlock.hash(),
                            secondBlock.hash()
                    ),
                    initialRequests
            );

            /*
             * Complete B2 FIRST.
             *
             * Coordinator now possesses B2, but B1 is still absent.
             * B2 therefore MUST NOT be processed yet.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            secondBlock
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * No horizon advancement is possible merely because B2
             * arrived. The left edge is still B1.
             *
             * Now return B1.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            firstBlock
                                    )
                            )
                    )
            );

            output.flush();

            /*
             * Once B1 is processed, B2 is already buffered and may be
             * processed immediately. That advances the horizon far
             * enough for B3 to be submitted.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    thirdBlock.hash()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            thirdBlock
                                    )
                            )
                    )
            );

            output.flush();

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runPeer(
            ServerSocket serverSocket,
            List<Block> blocks
    ) {
        runPeer(
                serverSocket,
                blocks,
                null
        );
    }

    private static void runPeer(
            ServerSocket serverSocket,
            List<Block> blocks,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output
            );

            /*
             * Several scheduler tasks may issue getdata
             * concurrently for the same peer.
             *
             * PeerConnection guarantees that complete P2P
             * messages are serialized on the wire, but the
             * relative order of different scheduler threads
             * is intentionally not guaranteed.
             *
             * Therefore this fake peer validates the complete
             * requested set rather than imposing B1, B2, B3...
             * as the wire order.
             */
            Map<Hash256, Block> expectedBlocks =
                    new HashMap<>();

            for (Block block : blocks) {

                Block previous =
                        expectedBlocks.put(
                                block.hash(),
                                block
                        );

                assertNull(
                        previous,
                        "Duplicate expected block hash "
                                + block.hash()
                                .toDisplayHex()
                );
            }

            Set<Hash256> requested =
                    new HashSet<>();

            for (int i = 0;
                 i < blocks.size();
                 i++) {

                BitcoinMessage getDataWire =
                        reader.read(
                                input
                        ).orElseThrow();

                assertEquals(
                        "getdata",
                        getDataWire.command()
                );

                GetDataMessage getData =
                        BitcoinMessages.decodeGetData(
                                getDataWire
                        );

                assertEquals(
                        1,
                        getData.size()
                );

                InventoryVector vector =
                        getData.inventory()
                                .get(0);

                assertEquals(
                        InventoryVector.MSG_WITNESS_BLOCK,
                        vector.type()
                );

                Hash256 requestedHash =
                        vector.hash();

                Block requestedBlock =
                        expectedBlocks.get(
                                requestedHash
                        );

                assertNotNull(
                        requestedBlock,
                        "Peer received unexpected block request "
                                + requestedHash
                                .toDisplayHex()
                );

                assertTrue(
                        requested.add(
                                requestedHash
                        ),
                        "Peer received duplicate block request "
                                + requestedHash
                                .toDisplayHex()
                );

                /*
                 * Reply to the block that was ACTUALLY
                 * requested, irrespective of request order.
                 */
                output.write(
                        encoder.encode(
                                BitcoinMessages.block(
                                        new BlockMessage(
                                                requestedBlock
                                        )
                                )
                        )
                );

                output.flush();
            }

            /*
             * Every expected block must have been requested
             * exactly once.
             */
            assertEquals(
                    expectedBlocks.keySet(),
                    requested
            );

            /*
             * Some tests need the connection to remain alive
             * while the caller verifies Peer.READY.
             */
            if (release != null) {

                assertTrue(
                        release.await(
                                5,
                                TimeUnit.SECONDS
                        ),
                        "Timed out waiting to release test peer"
                );
            }

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void performHandshake(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) throws Exception {

        BitcoinMessage version =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "version",
                version.command()
        );

        VersionMessage remoteVersion =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        TIME,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        REMOTE_NONCE,
                        "/block-sync-coordinator-test/",
                        0,
                        true
                );

        output.write(
                encoder.encode(
                        BitcoinMessages.version(
                                remoteVersion
                        )
                )
        );

        output.flush();

        assertEquals(
                "wtxidrelay",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "sendaddrv2",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "verack",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.wtxidRelay()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.sendAddrV2()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.verack()
                )
        );

        output.flush();
    }

    private static Peer connectPeer(
            int port
    ) throws Exception {

        PeerConnection connection =
                new PeerConnection(
                        PARAMETERS,
                        5_000,
                        5_000
                );

        Peer peer =
                new Peer(
                        connection,
                        VersionMessage.DEFAULT_SERVICES,
                        0,
                        true
                );

        try {
            peer.connect(
                    "127.0.0.1",
                    port
            );

            peer.handshake();

            assertTrue(
                    peer.isReady()
            );

            return peer;

        } catch (Exception exception) {

            try {
                peer.close();
            } catch (Exception closeException) {
                exception.addSuppressed(
                        closeException
                );
            }

            throw exception;
        }
    }

    private static void runNotFoundPeer(
            ServerSocket serverSocket,
            Hash256 expectedHash,
            long remoteNonce
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            assertRequestedBlock(
                    reader,
                    input,
                    expectedHash
            );

            NotFoundMessage notFound =
                    new NotFoundMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_WITNESS_BLOCK,
                                            expectedHash
                                    )
                            )
                    );

            output.write(
                    encoder.encode(
                            BitcoinMessages.notFound(
                                    notFound
                            )
                    )
            );

            output.flush();

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void performHandshake(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output,
            long remoteNonce
    ) throws Exception {

        BitcoinMessage version =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "version",
                version.command()
        );

        VersionMessage remoteVersion =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        TIME,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        remoteNonce,
                        "/block-sync-coordinator-test/",
                        0,
                        true
                );

        output.write(
                encoder.encode(
                        BitcoinMessages.version(
                                remoteVersion
                        )
                )
        );

        output.flush();

        assertEquals(
                "wtxidrelay",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "sendaddrv2",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "verack",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.wtxidRelay()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.sendAddrV2()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.verack()
                )
        );

        output.flush();
    }

    @Test
    void shouldDownloadBlocksConcurrentlyFromDifferentPeers()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        71
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        72
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch requestsReceived =
                    new CountDownLatch(
                            2
                    );

            CountDownLatch releaseBarrierPeers =
                    new CountDownLatch(
                            1
                    );

            CompletableFuture<Void> firstServerFuture =
                    CompletableFuture.runAsync(
                            () -> runBarrierBlockPeer(
                                    firstServer,
                                    block1,
                                    block1.hash(),
                                    0x4142434445464748L,
                                    requestsReceived,
                                    releaseBarrierPeers
                            )
                    );

            CompletableFuture<Void> secondServerFuture =
                    CompletableFuture.runAsync(
                            () -> runBarrierBlockPeer(
                                    secondServer,
                                    block2,
                                    block2.hash(),
                                    0x5152535455565758L,
                                    requestsReceived,
                                    releaseBarrierPeers
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            BlockDownloadService blockDownloadService =
                    new BlockDownloadService(
                            peerManager
                    );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            blockDownloadService,
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMinutes(10))
                    );

            List<Block> downloaded =
                    scheduler.download(
                            List.of(
                                    block1.hash(),
                                    block2.hash()
                            )
                    );

            assertEquals(
                    2,
                    downloaded.size()
            );

            /*
             * Scheduler must preserve the requested chain order
             * even though the two downloads happen concurrently.
             */
            assertEquals(
                    block1.hash(),
                    downloaded.get(0).hash()
            );

            assertEquals(
                    block2.hash(),
                    downloaded.get(1).hash()
            );

            assertTrue(
                    firstPeer.isReady()
            );

            assertTrue(
                    secondPeer.isReady()
            );

            releaseBarrierPeers.countDown();

            firstServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            secondServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldUseLocalBlocksAcrossDownloadWindowBoundaries()
            throws Exception {

        Block genesisBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisBlock.header()
                );

        Block block1 =
                child(
                        genesis,
                        121
                );

        BlockIndex index1 =
                BlockIndexFactory.createChild(
                        genesis,
                        block1.header()
                );

        Block block2 =
                child(
                        index1,
                        122
                );

        BlockIndex index2 =
                BlockIndexFactory.createChild(
                        index1,
                        block2.header()
                );

        Block block3 =
                child(
                        index2,
                        123
                );

        BlockIndex index3 =
                BlockIndexFactory.createChild(
                        index2,
                        block3.header()
                );

        Block block4 =
                child(
                        index3,
                        124
                );

        BlockIndex index4 =
                BlockIndexFactory.createChild(
                        index3,
                        block4.header()
                );

        try (RocksDbDatabase database =
                     new RocksDbDatabase(
                             directory.resolve(
                                     "block-sync-local-window-boundary"
                             )
                     );

             ServerSocket serverSocket =
                     new ServerSocket(0)) {

            NodeValidationService validationService =
                    new NodeValidationService(
                            database,
                            PARAMETERS,
                            () -> TIME + 10_000L,
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

            /*
             * Header synchronization already knows
             * the complete B1 -> B4 path.
             */
            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index1
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index2
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index3
                    )
            );

            indexStore.save(
                    BlockIndexStorageMapper.toStored(
                            index4
                    )
            );

            /*
             * B1 and B3 bodies already exist locally.
             *
             * B2 and B4 are deliberately absent.
             */
            blockStore.save(
                    block1
            );

            blockStore.save(
                    block3
            );

            assertTrue(
                    blockStore.find(
                            block1.hash()
                    ).isPresent()
            );

            assertTrue(
                    blockStore.find(
                            block2.hash()
                    ).isEmpty()
            );

            assertTrue(
                    blockStore.find(
                            block3.hash()
                    ).isPresent()
            );

            assertTrue(
                    blockStore.find(
                            block4.hash()
                    ).isEmpty()
            );

            RocksDbChainStateStore chainStateStore =
                    new RocksDbChainStateStore(
                            database
                    );

            chainStateStore.saveBestHeaderTipHash(
                    index4.hash()
            );

            BlockIndexLookup lookup =
                    new StoredBlockIndexLookup(
                            indexStore
                    );

            HeaderChainState headerChainState =
                    new HeaderChainState(
                            index4
                    );

            CountDownLatch releasePeer =
                    new CountDownLatch(
                            1
                    );

            /*
             * The peer has ONLY the bodies which are missing
             * from local storage.
             *
             * runPeer() verifies exact request order, therefore
             * any request for B1 or B3 fails this test.
             */
            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    List.of(
                                            block2,
                                            block4
                                    ),
                                    releasePeer
                            )
                    );

            try (PeerManager peerManager =
                         new PeerManager()) {

                Peer peer =
                        connectPeer(
                                serverSocket.getLocalPort()
                        );

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
                                        Duration.ofMinutes(10))
                        );

                BlockSyncCoordinator coordinator =
                        new BlockSyncCoordinator(
                                blockDownloadScheduler,
                                validationService,
                                headerChainState,
                                lookup,
                                blockStore,
                                2
                        );

                List<BlockIndex> processed =
                        coordinator.synchronize();

                /*
                 * Public result still represents the complete
                 * connect path, not merely network downloads.
                 */
                assertEquals(
                        4,
                        processed.size()
                );

                assertEquals(
                        block1.hash(),
                        processed.get(0).hash()
                );

                assertEquals(
                        block2.hash(),
                        processed.get(1).hash()
                );

                assertEquals(
                        block3.hash(),
                        processed.get(2).hash()
                );

                assertEquals(
                        block4.hash(),
                        processed.get(3).hash()
                );

                /*
                 * All four blocks must pass through the ordered
                 * consensus/connection path regardless of where
                 * their bodies came from.
                 */
                assertEquals(
                        block4.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        4L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        block4.hash(),
                        chainStateStore
                                .loadActiveTipHash()
                                .orElseThrow()
                );

                /*
                 * Network-downloaded bodies must now also
                 * have been persisted by BlockProcessor.
                 */
                assertTrue(
                        blockStore.find(
                                block2.hash()
                        ).isPresent()
                );

                assertTrue(
                        blockStore.find(
                                block4.hash()
                        ).isPresent()
                );

                assertTrue(
                        peer.isReady()
                );

                releasePeer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runBarrierBlockPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash,
            long remoteNonce,
            CountDownLatch requestsReceived,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            /*
             * Receive THIS peer's getdata first.
             */
            assertRequestedBlock(
                    reader,
                    input,
                    expectedHash
            );

            /*
             * Announce that this request has actually arrived.
             */
            requestsReceived.countDown();

            /*
             * Do NOT send the block until BOTH peers have
             * received their getdata request.
             *
             * Therefore this test cannot succeed if downloads
             * are sequential.
             */
            boolean bothRequestsReceived =
                    requestsReceived.await(
                            5,
                            TimeUnit.SECONDS
                    );

            assertTrue(
                    bothRequestsReceived,
                    "Both peers must receive getdata before either block is sent"
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            expectedBlock
                                    )
                            )
                    )
            );

            output.flush();

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release barrier peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runBlockPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash,
            long remoteNonce
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    PARAMETERS
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            PARAMETERS
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            performHandshake(
                    reader,
                    encoder,
                    input,
                    output,
                    remoteNonce
            );

            assertRequestedBlock(
                    reader,
                    input,
                    expectedHash
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new BlockMessage(
                                            expectedBlock
                                    )
                            )
                    )
            );

            output.flush();

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void assertRequestedBlock(
            BitcoinMessageStreamReader reader,
            BufferedInputStream input,
            Hash256 expectedHash
    ) throws Exception {

        BitcoinMessage getDataWire =
                reader.read(
                        input
                ).orElseThrow();

        assertEquals(
                "getdata",
                getDataWire.command()
        );

        GetDataMessage getData =
                BitcoinMessages.decodeGetData(
                        getDataWire
                );

        assertEquals(
                1,
                getData.size()
        );

        assertEquals(
                InventoryVector.MSG_WITNESS_BLOCK,
                getData.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                expectedHash,
                getData.inventory()
                        .get(0)
                        .hash()
        );
    }

    private static Block child(
            BlockIndex parent,
            int tag
    ) {

        int height =
                Math.toIntExact(
                        parent.height() + 1
                );

        Transaction coinbase =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                (byte) (0x50 + height),
                                                (byte) tag
                                        },
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        REWARD,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        List<Transaction> transactions =
                List.of(
                        coinbase
                );

        Hash256 root =
                MerkleTree.calculateRoot(
                        List.of(
                                coinbase.txId()
                        )
                );

        Block template =
                new Block(
                        new BlockHeader(
                                4,
                                parent.hash(),
                                root,
                                new UInt32(
                                        parent.header()
                                                .timestamp()
                                                .value() + 1
                                ),
                                new UInt32(
                                        BITS
                                ),
                                new UInt32(0)
                        ),
                        transactions
                );

        return withValidPow(
                template,
                parent.hash(),
                root
        );
    }

    private static Block withValidPow(
            Block block,
            Hash256 parent,
            Hash256 root
    ) {

        for (long nonce = 0;
             nonce < 100_000;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            parent,
                            root,
                            block.header()
                                    .timestamp(),
                            new UInt32(
                                    BITS
                            ),
                            new UInt32(
                                    nonce
                            )
                    );

            if (ProofOfWork.isValid(
                    header,
                    PARAMETERS
            )) {
                return new Block(
                        header,
                        block.transactions()
                );
            }
        }

        throw new AssertionError(
                "Could not construct regtest header"
        );
    }
}

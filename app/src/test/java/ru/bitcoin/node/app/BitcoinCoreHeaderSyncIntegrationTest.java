package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.PeerState;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.BlockSynchronizer;
import ru.bitcoin.node.p2p.sync.HeaderSynchronizer;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
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
                    "58fb5d854840e3d20f48f8226b56c2a6"
                            + "d6cba54e366a896de7d179fe70c34668"
            );

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

            HeaderBatchProcessor batchProcessor =
                    new HeaderBatchProcessor(
                            headerProcessor,
                            indexStore
                    );

            HeaderSyncService headerSyncService =
                    new HeaderSyncService(
                            batchProcessor
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

                BlockSynchronizer blockSynchronizer =
                        new BlockSynchronizer(
                                connection,
                                peer
                        );

                HeaderSynchronizer headerSynchronizer =
                        new HeaderSynchronizer(
                                connection,
                                peer
                        );

                /*
                 * Ask for everything after our current
                 * active genesis block.
                 */
                Hash256 locatorHash =
                        validationService
                                .activeTip()
                                .hash();

                Hash256 stopHash =
                        new Hash256(
                                new byte[Hash256.LENGTH]
                        );

                HeadersMessage headers =
                        headerSynchronizer.download(
                                List.of(
                                        locatorHash
                                ),
                                stopHash
                        );

                assertEquals(
                        3,
                        headers.size()
                );

                List<BlockIndex> processed =
                        headerSyncService.process(
                                headers
                        );

                assertEquals(
                        3,
                        processed.size()
                );

                assertEquals(
                        1L,
                        processed.get(0).height()
                );

                assertEquals(
                        2L,
                        processed.get(1).height()
                );

                assertEquals(
                        3L,
                        processed.get(2).height()
                );

                assertEquals(
                        REGTEST_GENESIS,
                        processed.get(0)
                                .previousBlockHash()
                );

                assertEquals(
                        processed.get(0).hash(),
                        processed.get(1)
                                .previousBlockHash()
                );

                assertEquals(
                        processed.get(1).hash(),
                        processed.get(2)
                                .previousBlockHash()
                );

                assertEquals(
                        EXPECTED_TIP,
                        processed.get(2).hash()
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
                        3L,
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
                 * Download and connect every block body whose
                 * header has already been validated and persisted.
                 *
                 * Bodies must be processed in chain order because
                 * block #2 cannot be connected before block #1, etc.
                 */
                for (int i = 0;
                     i < processed.size();
                     i++) {

                    BlockIndex expectedIndex =
                            processed.get(i);

                    Hash256 requestedBlockHash =
                            expectedIndex.hash();

                    var block =
                            blockSynchronizer.download(
                                    requestedBlockHash
                            );

                    assertEquals(
                            requestedBlockHash,
                            block.hash()
                    );

                    BlockProcessingResult result =
                            validationService.processBlock(
                                    block
                            );

                    assertEquals(
                            BlockProcessingResult.CONNECTED,
                            result
                    );

                    assertEquals(
                            expectedIndex.height(),
                            validationService
                                    .activeTip()
                                    .height()
                    );

                    assertEquals(
                            requestedBlockHash,
                            validationService
                                    .activeTip()
                                    .hash()
                    );

                    System.out.println(
                            "Block processing result at height "
                                    + expectedIndex.height()
                                    + ": "
                                    + result
                    );

                    System.out.println(
                            "Active block-chain height after block: "
                                    + validationService
                                    .activeTip()
                                    .height()
                    );

                    System.out.println(
                            "Active block-chain tip after block: "
                                    + validationService
                                    .activeTip()
                                    .hash()
                                    .toDisplayHex()
                    );
                }

                /*
                 * All downloaded headers now also have validated
                 * block bodies, so the active chain must reach the
                 * same tip as the header chain.
                 */
                assertEquals(
                        3L,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        EXPECTED_TIP,
                        validationService
                                .activeTip()
                                .hash()
                );

                assertEquals(
                        storedTip.hash(),
                        validationService
                                .activeTip()
                                .hash()
                );

                System.out.println(
                        "Header-chain tip: "
                                + storedTip
                                .hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Active block-chain tip: "
                                + validationService
                                .activeTip()
                                .hash()
                                .toDisplayHex()
                );

                System.out.println(
                        "Active block-chain height: "
                                + validationService
                                .activeTip()
                                .height()
                );
            }
        }
    }
}
package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.NetworkConfiguration;
import ru.bitcoin.node.app.config.NodeConfiguration;
import ru.bitcoin.node.app.service.NodeLifecycleService;
import ru.bitcoin.node.app.service.NodeLifecycleState;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.p2p.BitcoinClient;
import ru.bitcoin.node.p2p.OutboundPeerManager;
import ru.bitcoin.node.p2p.OutboundPeerSupervisor;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.sync.BlockDownloadScheduler;
import ru.bitcoin.node.p2p.sync.BlockDownloadService;
import ru.bitcoin.node.p2p.sync.BlockDownloadTimeoutPolicy;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeConfigurationTest {

    @Test
    void testnetProfileConfiguresDeeperBoundedDownloadPipeline() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
            for (var source : loader.load("testnet-profile",
                    new org.springframework.core.io.ClassPathResource("application-testnet.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-data",
                    Map.of("bitcoin.data-directory", directory.toString())));
            context.register(NetworkConfiguration.class, NodeConfiguration.class);
            context.refresh();
            assertEquals(16, context.getBean(BlockDownloadScheduler.class).maxBlocksInFlightPerPeer());
        }
    }
    @TempDir
    Path directory;

    @Test
    void explicitDataDirectoryInitializesPersistentNodeInfrastructureAndReopensIt() {

        for (int i = 0; i < 2; i++) {

            try (var context =
                         new AnnotationConfigApplicationContext()) {

                context.getEnvironment()
                        .getPropertySources()
                        .addFirst(
                                new MapPropertySource(
                                        "node-test",
                                        Map.of(
                                                "bitcoin.data-directory",
                                                directory.toString(),
                                                "bitcoin.network",
                                                "regtest"
                                        )
                                )
                        );

                context.register(
                        NetworkConfiguration.class,
                        NodeConfiguration.class
                );

                context.refresh();

                NodeValidationService validationService =
                        context.getBean(
                                NodeValidationService.class
                        );

                NodeSyncInfrastructure syncInfrastructure =
                        context.getBean(
                                NodeSyncInfrastructure.class
                        );

                PeerAddressManager peerAddressManager =
                        context.getBean(
                                PeerAddressManager.class
                        );

                PeerDiscovery peerDiscovery =
                        context.getBean(
                                PeerDiscovery.class
                        );

                PeerManager peerManager =
                        context.getBean(
                                PeerManager.class
                        );

                BitcoinClient bitcoinClient =
                        context.getBean(
                                BitcoinClient.class
                        );

                OutboundPeerManager outboundPeerManager =
                        context.getBean(
                                OutboundPeerManager.class
                        );

                BlockDownloadService blockDownloadService =
                        context.getBean(
                                BlockDownloadService.class
                        );

                BlockDownloadTimeoutPolicy timeoutPolicy =
                        context.getBean(
                                BlockDownloadTimeoutPolicy.class
                        );

                BlockDownloadScheduler scheduler =
                        context.getBean(
                                BlockDownloadScheduler.class
                        );

                BlockSyncCoordinator blockSyncCoordinator =
                        context.getBean(
                                BlockSyncCoordinator.class
                        );

                NodeLifecycleService lifecycleService =
                        context.getBean(
                                NodeLifecycleService.class
                        );

                assertNotNull(
                        lifecycleService
                );

                assertEquals(
                        NodeLifecycleState.NEW,
                        lifecycleService.state()
                );

                assertFalse(
                        lifecycleService.isRunning()
                );

                assertTrue(
                        lifecycleService.failure()
                                .isEmpty()
                );

                assertNotNull(
                        peerAddressManager
                );

                assertNotNull(
                        peerDiscovery
                );

                assertNotNull(
                        peerManager
                );

                assertNotNull(
                        bitcoinClient
                );

                assertNotNull(
                        outboundPeerManager
                );

                assertNotNull(
                        blockDownloadService
                );

                assertNotNull(
                        scheduler
                );

                assertNotNull(
                        blockSyncCoordinator
                );

                assertEquals(
                        Duration.ofSeconds(
                                context.getBean(
                                                NetworkParameters.class
                                        )
                                        .targetSpacingSeconds()
                        ),
                        timeoutPolicy.targetSpacing()
                );

                assertTrue(
                        peerManager.isEmpty()
                );

                assertEquals(
                        0,
                        validationService
                                .activeTip()
                                .height()
                );

                assertEquals(
                        0,
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .height()
                );

                assertEquals(
                        validationService
                                .activeTip()
                                .hash(),
                        syncInfrastructure
                                .headerChainState()
                                .bestHeaderTip()
                                .hash()
                );

                assertNotNull(
                        syncInfrastructure
                                .blockIndexLookup()
                );

                assertNotNull(
                        syncInfrastructure
                                .blockStore()
                );

                assertNotNull(
                        syncInfrastructure
                                .headerSyncService()
                );

                assertNotNull(
                        syncInfrastructure
                                .blockLocatorBuilder()
                );
            }
        }
    }

    @Test
    void outboundPeerSupervisorUsesEightFullRelayPeersByDefault() {
        try (var context = createContext(Map.of())) {
            OutboundPeerSupervisor supervisor =
                    context.getBean(OutboundPeerSupervisor.class);

            assertEquals(8, supervisor.targetOutboundPeers());
        }
    }

    @Test
    void outboundPeerSupervisorUsesConfiguredTargetPeerCount() {
        try (var context = createContext(
                Map.of("bitcoin.p2p.target-outbound-peers", "4")
        )) {
            OutboundPeerSupervisor supervisor =
                    context.getBean(OutboundPeerSupervisor.class);

            assertEquals(4, supervisor.targetOutboundPeers());
        }
    }

    @Test
    void outboundPeerSupervisorRejectsNonPositiveTargetPeerCount() {
        Exception exception = assertThrows(
                Exception.class,
                () -> {
                    try (var ignored = createContext(
                            Map.of("bitcoin.p2p.target-outbound-peers", "0")
                    )) {
                        // Context creation itself must fail.
                    }
                }
        );

        assertNotNull(exception);
    }

    @Test
    void springStartupFinalizesValidatedSnapshot() { verifySnapshotStartup(false, false); }

    @Test
    void springStartupResumesInterruptedSnapshotCopy() { verifySnapshotStartup(false, true); }

    @Test
    void springStartupRollsBackInvalidSnapshot() { verifySnapshotStartup(true, false); }

    private void verifySnapshotStartup(boolean invalid, boolean copying) {
        var params = ru.bitcoin.node.protocol.network.NetworkParametersRegistry.regtest();
        ru.bitcoin.node.common.types.Hash256 minedHash;
        try (var db = new ru.bitcoin.node.storage.rocksdb.RocksDbDatabase(snapshotChainstatePath(), params.magic());
             var node = new NodeValidationService(db, params, () -> 1_800_000_000L,
                     new ru.bitcoin.node.mempool.Mempool())) {
            var template = node.createMiningTemplate(new byte[]{0x51}, new byte[0], 4_000_000,
                    new ru.bitcoin.node.mempool.FeeRate(0));
            var mined = ru.bitcoin.node.mining.NonceMiner.search(template, params, 0, 100_000, () -> false).orElseThrow();
            node.processBlock(mined);
            minedHash = mined.hash();
            if (invalid) node.invalidateBlock(minedHash);
        }
        byte staging = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.SNAPSHOT_UTXO_STAGING;
        byte canonical = ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.UTXO;
        try (var db = new ru.bitcoin.node.storage.rocksdb.RocksDbDatabase(snapshotChainstatePath(), params.magic())) {
            if (invalid) {
                db.put(new byte[]{staging, 99}, new byte[]{42});
            } else {
                try (var batch = new ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch()) {
                    db.forEachEntryByPrefix(canonical, (key, value) -> {
                        byte[] target = key.clone();
                        target[0] = staging;
                        batch.put(target, value);
                    });
                    batch.deletePrefix(canonical);
                    if (copying) batch.put(new byte[]{
                            ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE, 1}, new byte[]{1});
                    db.write(batch);
                }
            }
            var tips = new ru.bitcoin.node.storage.chain.RocksDbChainStateStore(db);
            tips.saveActiveTipHash(params.genesisBlockHash());
            new ru.bitcoin.node.storage.utxo.RocksDbSnapshotChainStateStore(db)
                    .activate(params.genesisBlockHash(), 0, minedHash, 1);
            var background = new ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore(db);
            background.initialize(params.genesisBlockHash(), 0);
            background.mark(invalid
                            ? ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore.Status.INVALID
                            : ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore.Status.VALIDATED,
                    invalid ? params.genesisBlockHash() : minedHash, invalid ? 0 : 1);
        }
        for (int restart = 0; restart < 2; restart++) {
            try (var context = createContext(Map.of("bitcoin.node.auto-start", "false",
                    "bitcoin.p2p.listen", "false", "bitcoin.p2p.peers", ""))) {
                var node = context.getBean(NodeValidationService.class);
                var db = context.getBean(ru.bitcoin.node.storage.rocksdb.RocksDbDatabase.class);
                assertEquals(invalid ? params.genesisBlockHash() : minedHash, node.activeTip().hash());
                assertEquals(invalid ? 0 : 1, node.utxoSetInfo().txouts());
                assertEquals(invalid ? 0 : 5_000_000_000L, node.utxoSetInfo().totalAmount());
                assertTrue(new ru.bitcoin.node.storage.utxo.RocksDbSnapshotChainStateStore(db).load().isEmpty());
                assertTrue(new ru.bitcoin.node.storage.utxo.RocksDbAssumeUtxoBackgroundStore(db).load().isEmpty());
                assertNull(db.get(new byte[]{
                        ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces.ASSUMEUTXO_FINALIZATION_STATE, 1}));
                if (invalid) assertArrayEquals(new byte[]{42}, db.get(new byte[]{staging, 99}));
                else {
                    long[] entries = {0};
                    db.forEachEntryByPrefix(staging, (key, value) -> entries[0]++);
                    assertEquals(0, entries[0]);
                }
                assertNotNull(context.getBean(NodeSyncInfrastructure.class));
                assertEquals(NodeLifecycleState.NEW, context.getBean(NodeLifecycleService.class).state());
                assertTrue(context.getBean(PeerManager.class).isEmpty());
            }
        }
    }

    private Path snapshotChainstatePath() {
        // NodeConfiguration stores each network below bitcoin.data-directory and keeps
        // RocksDB in <network>/chainstate.  Build the fixture in the same physical
        // database that the Spring context will reopen.
        return directory.resolve("regtest").resolve("chainstate");
    }

    private AnnotationConfigApplicationContext createContext(
            Map<String, Object> overrides
    ) {
        var context = new AnnotationConfigApplicationContext();

        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("bitcoin.data-directory", directory.toString());
        properties.put("bitcoin.network", "regtest");
        properties.putAll(overrides);

        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "node-test",
                                properties
                        )
                );

        context.register(
                NetworkConfiguration.class,
                NodeConfiguration.class
        );
        context.refresh();
        return context;
    }

}
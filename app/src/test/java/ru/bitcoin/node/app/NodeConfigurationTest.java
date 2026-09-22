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
    void outboundPeerSupervisorUsesSinglePeerByDefault() {
        try (var context = createContext(Map.of())) {
            OutboundPeerSupervisor supervisor =
                    context.getBean(OutboundPeerSupervisor.class);

            assertEquals(1, supervisor.targetOutboundPeers());
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
package ru.bitcoin.node.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.service.NodeLifecycleRunner;
import ru.bitcoin.node.app.service.NodeLifecycleService;
import ru.bitcoin.node.app.service.NodeLifecycleSpringAdapter;
import ru.bitcoin.node.app.sync.BlockSyncCoordinator;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.address.PeerAddressProtocol;
import ru.bitcoin.node.p2p.sync.BlockDownloadScheduler;
import ru.bitcoin.node.p2p.sync.BlockDownloadService;
import ru.bitcoin.node.p2p.sync.BlockDownloadTimeoutPolicy;
import ru.bitcoin.node.p2p.sync.PeerDiscovery;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A data directory explicitly enables persistent node state.
 */
@Configuration
@ConditionalOnProperty(name = "bitcoin.data-directory")
public class NodeConfiguration {
    @Bean(destroyMethod = "close")
    public ru.bitcoin.node.app.service.NodeRelayService nodeRelayService(NodeValidationService validation,
                                                                         NodeSyncInfrastructure infrastructure, PeerManager peers) {
        return new ru.bitcoin.node.app.service.NodeRelayService(validation, infrastructure, peers);
    }

    @Bean(destroyMethod = "close")
    public RocksDbDatabase chainDatabase(
            @Value("${bitcoin.data-directory}")
            String path
    ) {
        return new RocksDbDatabase(
                Path.of(path)
        );
    }

    @Bean
    public AdjustedTime adjustedTime() {
        return () ->
                Instant.now()
                        .getEpochSecond();
    }

    @Bean
    public NodeValidationService nodeValidationService(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        return new NodeValidationService(
                database,
                parameters,
                adjustedTime,
                new Mempool()
        );
    }

    @Bean
    public NodeSyncInfrastructure nodeSyncInfrastructure(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        return new NodeSyncInfrastructure(
                database,
                parameters,
                adjustedTime
        );
    }

    @Bean
    public PeerAddressManager peerAddressManager(
            NetworkParameters parameters,
            @Value("${bitcoin.p2p.peers:}")
            String configuredPeers
    ) {

        PeerAddressManager addressManager =
                new PeerAddressManager();

        if (configuredPeers == null
                || configuredPeers.isBlank()) {

            return addressManager;
        }

        List<String> peers =
                List.of(
                        configuredPeers.split(",")
                );

        ConfiguredPeerLoader configuredPeerLoader =
                new ConfiguredPeerLoader(
                        parameters,
                        addressManager
                );

        configuredPeerLoader.load(
                peers
        );

        return addressManager;
    }

    @Bean
    public OutboundPeerSupervisor outboundPeerSupervisor(
            OutboundPeerManager outboundPeerManager,
            NodeValidationService validationService,
            @Value("${bitcoin.p2p.target-outbound-peers:1}")
            int targetOutboundPeers,
            @Value("${bitcoin.p2p.target-block-relay-peers:2}")
            int targetBlockRelayPeers
    ) {

        return new OutboundPeerSupervisor(
                outboundPeerManager,
                () -> Math.toIntExact(
                        validationService
                                .activeTip()
                                .height()
                ),
                targetOutboundPeers,
                targetBlockRelayPeers
        );
    }

    @Bean
    public PeerDiscovery peerDiscovery(
            NetworkParameters parameters,
            PeerAddressManager peerAddressManager
    ) {
        return new PeerDiscovery(
                parameters,
                peerAddressManager
        );
    }

    @Bean(destroyMethod = "close")
    public PeerManager peerManager() {
        return new PeerManager();
    }

    @Bean
    public BitcoinClient bitcoinClient(
            NetworkParameters parameters
    ) {
        return new BitcoinClient(
                parameters
        );
    }

    @Bean
    public OutboundPeerManager outboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager peerAddressManager
    ) {
        return new OutboundPeerManager(
                bitcoinClient,
                peerManager,
                peerAddressManager
        );
    }

    @Bean
    public BlockDownloadService blockDownloadService(
            PeerManager peerManager
    ) {
        return new BlockDownloadService(
                peerManager
        );
    }

    @Bean
    public BlockDownloadTimeoutPolicy blockDownloadTimeoutPolicy(
            NetworkParameters parameters
    ) {
        return new BlockDownloadTimeoutPolicy(
                Duration.ofSeconds(
                        parameters.targetSpacingSeconds()
                )
        );
    }

    @Bean
    public BlockDownloadScheduler blockDownloadScheduler(
            PeerManager peerManager,
            BlockDownloadService blockDownloadService,
            BlockDownloadTimeoutPolicy timeoutPolicy
    ) {
        return new BlockDownloadScheduler(
                peerManager,
                blockDownloadService,
                timeoutPolicy
        );
    }

    @Bean
    public BlockSyncCoordinator blockSyncCoordinator(
            BlockDownloadScheduler blockDownloadScheduler,
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure
    ) {
        return new BlockSyncCoordinator(
                blockDownloadScheduler,
                validationService,
                syncInfrastructure.headerChainState(),
                syncInfrastructure.blockIndexLookup(),
                syncInfrastructure.blockStore()
        );
    }

    @Bean
    public NodeLifecycleService nodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerDiscovery peerDiscovery,
            OutboundPeerManager outboundPeerManager,
            OutboundPeerSupervisor outboundPeerSupervisor,
            PeerManager peerManager,
            BitcoinServer bitcoinServer,
            BlockSyncCoordinator blockSyncCoordinator,
            NetworkParameters parameters,
            @Value("${bitcoin.p2p.listen:true}")
            boolean listen,
            @Value("${bitcoin.p2p.port:0}")
            int listenPort,
            @Value("${bitcoin.p2p.header-response-timeout-millis:10000}")
            long headerTimeoutMillis
    ) {

        Duration headerResponseTimeout =
                Duration.ofMillis(
                        headerTimeoutMillis
                );

        int effectiveListenPort =
                listenPort == 0
                        ? parameters.defaultPort()
                        : listenPort;

        return new NodeLifecycleService(
                validationService,
                syncInfrastructure,
                addressManager,
                peerDiscovery,
                outboundPeerManager,
                outboundPeerSupervisor,
                peerManager,
                bitcoinServer,
                blockSyncCoordinator,
                headerResponseTimeout,
                listen,
                effectiveListenPort
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "bitcoin.node.auto-start",
            havingValue = "true"
    )
    public NodeLifecycleRunner nodeLifecycleRunner(
            NodeLifecycleService lifecycleService
    ) {
        return new NodeLifecycleRunner(
                lifecycleService
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "bitcoin.node.auto-start",
            havingValue = "true"
    )
    public NodeLifecycleSpringAdapter nodeLifecycleSpringAdapter(
            NodeLifecycleRunner runner
    ) {
        return new NodeLifecycleSpringAdapter(
                runner
        );
    }

    @Bean
    public BitcoinServer bitcoinServer(
            NetworkParameters parameters,
            PeerManager peerManager,
            @Value("${bitcoin.p2p.max-inbound-peers:32}")
            int maxInboundPeers
    ) {
        return new BitcoinServer(
                parameters,
                peerManager,
                ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES,
                true,
                maxInboundPeers
        );
    }

    @Bean
    public PeerAddressProtocol peerAddressProtocol(
            PeerAddressManager peerAddressManager,
            PeerManager peerManager
    ) {

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        peerAddressManager,
                        peerManager
                );

        peerManager.addPeerListener(
                peer ->
                        peer.addMessageListener(
                                protocol
                        )
        );

        return protocol;
    }
}

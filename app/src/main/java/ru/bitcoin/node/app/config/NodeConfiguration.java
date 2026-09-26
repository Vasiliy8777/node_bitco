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
import ru.bitcoin.node.p2p.address.PeerAddressManagerStore;
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
import java.io.IOException;
import java.util.List;

/**
 * A data directory explicitly enables persistent node state.
 */
@Configuration
@ConditionalOnProperty(name = "bitcoin.data-directory")
public class NodeConfiguration {
    @Bean(destroyMethod = "close")
    public ru.bitcoin.node.app.service.NodeRelayService nodeRelayService(
            NodeValidationService validation,
            NodeSyncInfrastructure infrastructure,
            PeerManager peers,
            BlockDownloadScheduler blockDownloadScheduler
    ) {
        return new ru.bitcoin.node.app.service.NodeRelayService(
                validation,
                infrastructure,
                peers,
                blockDownloadScheduler
        );
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
            AdjustedTime adjustedTime,
            @Value("${bitcoin.reindex:false}")
            boolean fullReindex,
            @Value("${bitcoin.reindex-chainstate:false}")
            boolean reindexChainstate,
            @Value("${bitcoin.prune:0}")
            long pruneMiB,
            @Value("${bitcoin.assume-valid:}")
            String assumeValid,
            @Value("${bitcoin.persist-mempool:true}")
            boolean persistMempool,
            @Value("${bitcoin.txindex:false}")
            boolean txIndex
    ) {
        long pruneTargetBytes = pruneTargetBytes(pruneMiB);
        var pruneState = new ru.bitcoin.node.storage.chain.RocksDbPruneStateStore(database);
        if (txIndex && pruneMiB > 0) {
            throw new IllegalArgumentException("bitcoin.txindex is incompatible with bitcoin.prune");
        }
        if (txIndex && pruneState.hasPruned()) {
            throw new IllegalStateException("bitcoin.txindex cannot be enabled after historical block data has been pruned; restore/redownload full block history first");
        }
        if (fullReindex && reindexChainstate) {
            throw new IllegalArgumentException(
                    "bitcoin.reindex and bitcoin.reindex-chainstate cannot both be enabled");
        }
        if ((fullReindex || reindexChainstate) && pruneState.hasPruned()) {
            throw new IllegalStateException(
                    "reindex cannot rebuild from locally pruned historical block data");
        }

        var fullReindexer = new ru.bitcoin.node.chain.FullReindexer(
                database, parameters, adjustedTime);
        var reindexer = new ru.bitcoin.node.chain.ChainstateReindexer(
                database, parameters, adjustedTime);

        /* A full-reindex marker has priority because its reset removes the block index
         * that reindex-chainstate requires. Both rebuilds are restart-safe. */
        if (fullReindex || fullReindexer.isInProgress()) {
            fullReindexer.rebuild();
        } else if (reindexChainstate || reindexer.isInProgress()) {
            reindexer.rebuild();
        }

        new ru.bitcoin.node.chain.ChainstateConsistencyChecker(
                database,
                parameters,
                ru.bitcoin.node.chain.ChainstateConsistencyChecker.DEFAULT_REORG_SAFETY_DEPTH
        ).verify();

        // Databases created before persistent validation-status metadata existed need a
        // restart-safe backfill before RPC/fork diagnostics can classify old validated branches.
        new ru.bitcoin.node.chain.BlockValidationStatusMigrator(database).migrate();

        return new NodeValidationService(
                database,
                parameters,
                adjustedTime,
                new Mempool(),
                pruneTargetBytes,
                assumeValidHash(assumeValid, parameters),
                persistMempool,
                txIndex
        );
    }

    @Bean
    public NodeSyncInfrastructure nodeSyncInfrastructure(
            RocksDbDatabase database,
            NetworkParameters parameters,
            AdjustedTime adjustedTime,
            NodeValidationService validationService
    ) {
        return new NodeSyncInfrastructure(
                database,
                parameters,
                adjustedTime
        );
    }

    @Bean
    public PeerAddressManagerStore peerAddressManagerStore(
            @Value("${bitcoin.data-directory}") String dataDirectory,
            NetworkParameters parameters
    ) {
        return new PeerAddressManagerStore(Path.of(dataDirectory), parameters.magic());
    }

    @Bean
    public PeerAddressManager peerAddressManager(
            NetworkParameters parameters,
            PeerAddressManagerStore store,
            @Value("${bitcoin.p2p.peers:}")
            String configuredPeers
    ) {

        PeerAddressManager addressManager;
        try {
            addressManager = store.load().orElseGet(PeerAddressManager::new);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load persistent AddrMan", exception);
        }

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
    public PeerManager peerManager(@Value("${bitcoin.data-directory}") String dataDirectory) {
        return new PeerManager(
                new PeerDiscouragementManager(),
                new PeerBanManager(Path.of(dataDirectory))
        );
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public PeerLivenessService peerLivenessService(
            PeerManager peerManager
    ) {
        return new PeerLivenessService(peerManager);
    }

    @Bean
    public BitcoinClient bitcoinClient(
            NetworkParameters parameters,
            @Value("${bitcoin.prune:0}") long pruneMiB
    ) {
        long services = pruneMiB > 0
                ? ru.bitcoin.node.p2p.message.VersionMessage.NODE_WITNESS
                | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED
                : ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES;
        return new BitcoinClient(parameters, services, true);
    }

    @Bean
    public OutboundPeerManager outboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager peerAddressManager,
            NodeValidationService validationService
    ) {
        return new OutboundPeerManager(
                bitcoinClient,
                peerManager,
                peerAddressManager,
                validationService::isInitialBlockDownload
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
            NodeSyncInfrastructure syncInfrastructure,
            ru.bitcoin.node.app.service.NodeRelayService nodeRelayService
    ) {
        return new BlockSyncCoordinator(
                blockDownloadScheduler,
                validationService,
                syncInfrastructure.headerChainState(),
                syncInfrastructure.blockIndexLookup(),
                syncInfrastructure.blockStore(),
                1024,
                nodeRelayService::onConnectedBlock
        );
    }

    @Bean
    public NodeLifecycleService nodeLifecycleService(
            NodeValidationService validationService,
            NodeSyncInfrastructure syncInfrastructure,
            PeerAddressManager addressManager,
            PeerAddressManagerStore addressManagerStore,
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
            long headerTimeoutMillis,
            @Value("${bitcoin.mempool-checkpoint-interval-seconds:900}")
            long mempoolCheckpointIntervalSeconds
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
                addressManagerStore,
                peerDiscovery,
                outboundPeerManager,
                outboundPeerSupervisor,
                peerManager,
                bitcoinServer,
                blockSyncCoordinator,
                headerResponseTimeout,
                listen,
                effectiveListenPort,
                parameters.minimumChainWork(),
                checkpointInterval(mempoolCheckpointIntervalSeconds)
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
    public ru.bitcoin.node.p2p.address.PeerAddressRelayManager peerAddressRelayManager(
            PeerManager peerManager
    ) {
        return new ru.bitcoin.node.p2p.address.PeerAddressRelayManager(peerManager);
    }

    @Bean
    public PeerAddressProtocol peerAddressProtocol(
            PeerAddressManager peerAddressManager,
            PeerManager peerManager,
            ru.bitcoin.node.p2p.address.PeerAddressRelayManager peerAddressRelayManager
    ) {

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        peerAddressManager,
                        peerManager,
                        peerAddressRelayManager
                );

        peerManager.addPeerListener(
                peer -> {
                    peer.addMessageListener(
                            protocol
                    );
                    protocol.onPeerManaged(
                            peer
                    );
                }
        );

        return protocol;
    }
    static ru.bitcoin.node.common.types.Hash256 assumeValidHash(
            String configured, NetworkParameters parameters) {
        java.util.Objects.requireNonNull(parameters, "parameters");
        if (configured == null || configured.isBlank()) return parameters.defaultAssumeValid();
        String value = configured.trim();
        if (value.equals("0")) return new ru.bitcoin.node.common.types.Hash256(new byte[32]);
        if (!value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("bitcoin.assume-valid must be 0 or a 64-character block hash");
        }
        return ru.bitcoin.node.common.types.Hash256.fromDisplayHex(value);
    }

    static long pruneTargetBytes(long pruneMiB) {
        if (pruneMiB < 0) throw new IllegalArgumentException("bitcoin.prune must not be negative");
        if (pruneMiB == 1) return ru.bitcoin.node.chain.BlockPruner.MANUAL_ONLY;
        if (pruneMiB > 0 && pruneMiB < 550) {
            throw new IllegalArgumentException("bitcoin.prune must be 0, 1 (manual), or at least 550 MiB");
        }
        return Math.multiplyExact(pruneMiB, 1024L * 1024L);
    }


    static Duration checkpointInterval(long seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("bitcoin.mempool-checkpoint-interval-seconds must not be negative");
        }
        return Duration.ofSeconds(seconds);
    }
}

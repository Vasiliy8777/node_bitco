package ru.bitcoin.node.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.HexFormat;

@Configuration
@ConditionalOnProperty(name = "bitcoin.rpc.enabled", havingValue = "true")
public class MiningConfiguration {
    @Bean(destroyMethod = "close")
    public NodeRpcServer nodeRpcServer(NodeValidationService validation, NodeRelayService relay,
                                       NodeSyncInfrastructure sync, NodeLifecycleService lifecycle, NetworkParameters parameters,
                                       ru.bitcoin.node.p2p.PeerManager peerManager,
                                       @Value("${bitcoin.rpc.bind:127.0.0.1}") String bind,
                                       @Value("${bitcoin.rpc.port:8332}") int port,
                                       @Value("${bitcoin.rpc.user:bitcoin}") String user,
                                       @Value("${bitcoin.rpc.password:}") String password,
                                       @Value("${bitcoin.mining.payout-script:}") String payout,
                                       @Value("${bitcoin.mining.maximum-weight:3996000}") long maximumWeight,
                                       @Value("${bitcoin.mining.minimum-fee-sat-per-kvb:1000}") long minimumFee,
                                       @Value("${bitcoin.prune:0}") long pruneMiB,
                                       @Value("${bitcoin.blockfilterindex:false}") boolean blockFilterIndex,
                                       @Value("${bitcoin.p2p.peer-block-filters:false}") boolean peerBlockFilters,
                                       @Value("${bitcoin.p2p.v2-transport:false}") boolean v2Transport) throws IOException {
        var controller = new MiningController(validation, relay, parameters, lifecycle::isMiningReady,
                HexFormat.of().parseHex(payout), maximumWeight, new FeeRate(minimumFee));
        long services = pruneMiB > 0
                ? ru.bitcoin.node.p2p.message.VersionMessage.NODE_WITNESS
                | ru.bitcoin.node.p2p.message.VersionMessage.NODE_NETWORK_LIMITED
                : ru.bitcoin.node.p2p.message.VersionMessage.DEFAULT_SERVICES;
        if (blockFilterIndex && peerBlockFilters) services |= ru.bitcoin.node.p2p.message.VersionMessage.NODE_COMPACT_FILTERS;
        if (v2Transport) services |= ru.bitcoin.node.p2p.message.VersionMessage.NODE_P2P_V2;
        return new NodeRpcServer(new InetSocketAddress(bind, port), user, password, controller,
                validation, relay, sync, lifecycle::isMiningReady, peerManager, services);
    }
}

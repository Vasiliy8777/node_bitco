package ru.bitcoin.node.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.stratum.StratumServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.HexFormat;

@Configuration
@ConditionalOnProperty(name = "bitcoin.stratum.enabled", havingValue = "true")
public class StratumConfiguration {
    @Bean(destroyMethod = "close")
    public StratumServer stratumServer(NodeValidationService validation, NodeRelayService relay,
            NodeLifecycleService lifecycle, NetworkParameters parameters, AdjustedTime time,
            @Value("${bitcoin.stratum.bind:127.0.0.1}") String bind,
            @Value("${bitcoin.stratum.port:3333}") int port,
            @Value("${bitcoin.stratum.user:miner}") String user,
            @Value("${bitcoin.stratum.password:}") String password,
            @Value("${bitcoin.stratum.difficulty:65536}") BigDecimal difficulty,
            @Value("${bitcoin.stratum.maximum-connections:64}") int maximumConnections,
            @Value("${bitcoin.mining.payout-script:}") String payout,
            @Value("${bitcoin.mining.maximum-weight:3996000}") long maximumWeight,
            @Value("${bitcoin.mining.minimum-fee-sat-per-kvb:1000}") long minimumFee) throws IOException {
        var backend = new StratumMiningBackend(validation, relay, parameters, time, lifecycle::isMiningReady,
                HexFormat.of().parseHex(payout), maximumWeight, new FeeRate(minimumFee));
        return new StratumServer(new InetSocketAddress(bind, port), backend, user, password, difficulty, maximumConnections);
    }
}

package ru.bitcoin.node.rvn.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChain;
import ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService;
import ru.bitcoin.node.rvn.app.sync.RvnBlockDownloader;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.storage.RvnHeaderStore;
import java.nio.file.Path;
import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "ravencoin.sync.enabled", havingValue = "true")
public class RvnSyncConfiguration {
    @Bean(destroyMethod = "close")
    RvnHeaderStore rvnHeaderStore(RvnJavaPowEngine engine, RvnKawpowLightEngine kawpow,
            @Value("${ravencoin.network:mainnet}") String network,
            @Value("${ravencoin.data-directory:./data/ravencoin/mainnet}") String directory,
            @Value("${ravencoin.mining.enabled:false}") boolean mining,
            @Value("${ravencoin.p2p.probe-enabled:false}") boolean probe,
            @Value("${ravencoin.p2p.session-enabled:false}") boolean session) throws Exception {
        if (RvnNetwork.parse(network) != RvnNetwork.MAINNET)
            throw new IllegalArgumentException("Header synchronization currently supports RVN mainnet only");
        if (mining) throw new IllegalStateException("RVN mining NOT implemented; header sync cannot enable mining");
        if (probe || session) throw new IllegalArgumentException("Choose header sync or a diagnostic probe/session");
        RvnHistoricalPowSelfTest.requireValid(engine);
        RvnKawpowSelfTest.requireValid(kawpow);
        return new RvnHeaderStore(Path.of(directory).resolve("headers"), RvnMainnetExpectedBits.GENESIS_ID,
                RvnHistoricalPowSelfTest.mainnetGenesisHeader(), RvnMainnetDifficulty.POW_LIMIT);
    }

    @Bean
    RvnMainnetHeaderChain rvnMainnetHeaderChain(RvnHeaderStore store, RvnJavaPowEngine engine) {
        return new RvnMainnetHeaderChain(store, engine, () -> System.currentTimeMillis() / 1000);
    }

    @Bean
    RvnHeaderSyncService rvnHeaderSyncService(RvnMainnetHeaderChain chain, RvnHeaderStore store,
            @Value("${ravencoin.sync.peers:seed-raven.bitactivate.com,seed-raven.ravencoin.com,seed-raven.ravencoin.org}") String peers,
            @Value("${ravencoin.p2p.port:0}") int port,
            @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout,
            @Value("${ravencoin.sync.response-timeout-ms:30000}") long responseTimeout,
            @Value("${ravencoin.sync.poll-ms:10000}") long poll,
            @Value("${ravencoin.sync.blocks-enabled:false}") boolean blocks) {
        return new RvnHeaderSyncService(chain, Arrays.stream(peers.split(",")).map(String::trim).toList(),
                port == 0 ? RvnNetwork.MAINNET.port() : port, timeout, responseTimeout, poll,
                blocks ? new RvnBlockDownloader(store, responseTimeout) : null);
    }
}

package ru.bitcoin.node.rvn.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChain;
import ru.bitcoin.node.rvn.app.chain.RvnNativeBlockReplay;
import ru.bitcoin.node.rvn.storage.RvnCoinStore;
import ru.bitcoin.node.rvn.storage.RvnFlatFileStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService;
import ru.bitcoin.node.rvn.app.sync.RvnBlockDownloader;
import ru.bitcoin.node.rvn.app.sync.RvnSeedResolver;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.storage.RvnHeaderStore;
import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "ravencoin.sync.enabled", havingValue = "true")
public class RvnSyncConfiguration {
    @Bean(destroyMethod = "close")
    RvnFlatFileStore rvnBlockFiles(RvnDataDirectories directories) throws Exception {
        return new RvnFlatFileStore(directories.blocks(), "BLOCKS\n" + RvnMainnetExpectedBits.GENESIS_ID, 8_000_000);
    }

    @Bean(destroyMethod = "close")
    RvnHeaderStore rvnHeaderStore(RvnJavaPowEngine engine, RvnKawpowLightEngine kawpow, RvnDataDirectories directories,
            @Qualifier("rvnBlockFiles") RvnFlatFileStore bodyFiles,
            @Value("${ravencoin.network:mainnet}") String network,
            @Value("${ravencoin.p2p.probe-enabled:false}") boolean probe,
            @Value("${ravencoin.p2p.session-enabled:false}") boolean session) throws Exception {
        if (RvnNetwork.parse(network) != RvnNetwork.MAINNET)
            throw new IllegalArgumentException("Header synchronization currently supports RVN mainnet only");
        if (probe || session) throw new IllegalArgumentException("Choose header sync or a diagnostic probe/session");
        RvnHistoricalPowSelfTest.requireValid(engine);
        RvnKawpowSelfTest.requireValid(kawpow);
        return new RvnHeaderStore(directories.headers(), RvnMainnetExpectedBits.GENESIS_ID,
                RvnHistoricalPowSelfTest.mainnetGenesisHeader(), RvnMainnetDifficulty.POW_LIMIT, bodyFiles);
    }

    @Bean
    RvnMainnetHeaderChain rvnMainnetHeaderChain(RvnHeaderStore store, RvnJavaPowEngine engine) {
        return new RvnMainnetHeaderChain(store, engine, () -> System.currentTimeMillis() / 1000);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ravencoin.validation.native-replay-enabled", havingValue = "true")
    RvnFlatFileStore rvnUndoFiles(RvnDataDirectories directories) throws Exception {
        return new RvnFlatFileStore(directories.undo(), "UNDO\n" + RvnMainnetExpectedBits.GENESIS_ID, 64 * 1024 * 1024);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ravencoin.validation.native-replay-enabled", havingValue = "true")
    RvnCoinStore rvnCoinStore(RvnDataDirectories directories, RvnHeaderStore headers,
            @Qualifier("rvnUndoFiles") RvnFlatFileStore undoFiles) throws Exception {
        // Header storage is opened/self-tested first; failures cannot create an orphan coin DB.
        return new RvnCoinStore(directories.coins(), RvnMainnetExpectedBits.GENESIS_ID, undoFiles);
    }

    @Bean
    @ConditionalOnProperty(name = "ravencoin.validation.native-replay-enabled", havingValue = "true")
    RvnNativeBlockReplay rvnNativeBlockReplay(RvnHeaderStore headers, RvnCoinStore coins,
            @Value("${ravencoin.validation.canonical-asset-replay-enabled:false}") boolean canonicalAssets) throws Exception {
        return new RvnNativeBlockReplay(headers, coins, canonicalAssets);
    }

    @Bean
    RvnHeaderSyncService rvnHeaderSyncService(RvnMainnetHeaderChain chain, RvnHeaderStore store,
            ObjectProvider<RvnNativeBlockReplay> replay,
            @Value("${ravencoin.sync.peers:seed-raven.bitactivate.com,seed-raven.ravencoin.com,seed-raven.ravencoin.org}") String peers,
            @Value("${ravencoin.p2p.port:0}") int port,
            @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout,
            @Value("${ravencoin.sync.response-timeout-ms:30000}") long responseTimeout,
            @Value("${ravencoin.sync.poll-ms:10000}") long poll,
            @Value("${ravencoin.sync.dns-over-https:}") String dnsOverHttps,
            @Value("${ravencoin.sync.blocks-enabled:false}") boolean blocks) {
        return new RvnHeaderSyncService(chain, Arrays.stream(peers.split(",")).map(String::trim).toList(),
                port == 0 ? RvnNetwork.MAINNET.port() : port, timeout, responseTimeout, poll,
                blocks ? new RvnBlockDownloader(store, responseTimeout) : null, replay.getIfAvailable(), new RvnSeedResolver(dnsOverHttps));
    }
}

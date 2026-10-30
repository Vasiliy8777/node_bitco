package ru.bitcoin.node.rvn.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.app.chain.RvnMainnetHeaderChain;
import ru.bitcoin.node.rvn.app.chain.RvnHeaderPowBatch;
import ru.bitcoin.node.rvn.app.chain.RvnGpuHeaderVerifier;
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
    RvnMainnetHeaderChain rvnMainnetHeaderChain(RvnHeaderStore store, RvnJavaPowEngine engine, RvnHeaderPowBatch powBatch) {
        return new RvnMainnetHeaderChain(store, engine, () -> System.currentTimeMillis() / 1000, powBatch);
    }

    @Bean(destroyMethod = "close")
    RvnHeaderPowBatch rvnHeaderPowBatch(@Value("${ravencoin.sync.pow-workers:4}") int workers,
                                      @Value("${ravencoin.pow.cached-dag-items:65536}") int capacity,
                                      ObjectProvider<RvnGpuHeaderVerifier> gpu) {
        return new RvnHeaderPowBatch(workers, () -> new RvnJavaPowEngine(new RvnKawpowLightEngine(capacity)), gpu.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ravencoin.sync.gpu-pow-enabled", havingValue = "true")
    RvnGpuHeaderVerifier rvnGpuHeaderVerifier(RvnCudaKawpowWorker worker) {
        return new RvnGpuHeaderVerifier(worker);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ravencoin.validation.native-replay-enabled", havingValue = "true")
    RvnFlatFileStore rvnUndoFiles(RvnDataDirectories directories) throws Exception {
        return new RvnFlatFileStore(directories.undo(), "UNDO\n" + RvnMainnetExpectedBits.GENESIS_ID, 64 * 1024 * 1024);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ravencoin.validation.native-replay-enabled", havingValue = "true")
    RvnCoinStore rvnCoinStore(RvnDataDirectories directories, RvnHeaderStore headers,
            @Qualifier("rvnUndoFiles") RvnFlatFileStore undoFiles,
            @Value("${ravencoin.sync.coin-cache-mib:1024}") int cacheMiB) throws Exception {
        // Header storage is opened/self-tested first; failures cannot create an orphan coin DB.
        return new RvnCoinStore(directories.coins(), RvnMainnetExpectedBits.GENESIS_ID, undoFiles, cacheMiB);
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
            @Value("${ravencoin.sync.peers:2602:ffb8::208:72:56:214,51.79.161.39,seed-raven.bitactivate.com,seed-raven.ravencoin.com,seed-raven.ravencoin.org}") String peers,
            @Value("${ravencoin.p2p.port:0}") int port,
            @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout,
            @Value("${ravencoin.sync.response-timeout-ms:30000}") long responseTimeout,
            @Value("${ravencoin.sync.poll-ms:2000}") long poll,
            @Value("${ravencoin.sync.dns-over-https:}") String dnsOverHttps,
            @Value("${ravencoin.sync.blocks-enabled:false}") boolean blocks,
            @Value("${ravencoin.sync.headers-first:true}") boolean headersFirst,
            @Value("${ravencoin.sync.header-lead-blocks:4096}") int headerLead,
            @Value("${ravencoin.sync.body-batches-per-header:4}") int bodyBatchesPerHeader,
            @Value("${ravencoin.sync.minimum-peer-kib-per-second:64}") int minimumPeerKiBPerSecond,
            @Value("${ravencoin.sync.block-in-flight:32}") int inFlight) {
        return new RvnHeaderSyncService(chain, Arrays.stream(peers.split(",")).map(String::trim).toList(),
                port == 0 ? RvnNetwork.MAINNET.port() : port, timeout, responseTimeout, poll,
                blocks ? new RvnBlockDownloader(store, responseTimeout, inFlight) : null, replay.getIfAvailable(), new RvnSeedResolver(dnsOverHttps), headerLead, bodyBatchesPerHeader, minimumPeerKiBPerSecond, headersFirst);
    }
}

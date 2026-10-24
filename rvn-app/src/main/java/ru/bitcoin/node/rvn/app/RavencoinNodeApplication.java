package ru.bitcoin.node.rvn.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.p2p.RvnPeerProbe;
import ru.bitcoin.node.rvn.p2p.RvnPeerSession;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.pow.RvnKawpowLightEngine;
import ru.bitcoin.node.rvn.pow.RvnKawpowSelfTest;
import ru.bitcoin.node.rvn.pow.RvnJavaPowEngine;
import ru.bitcoin.node.rvn.pow.RvnHistoricalPowSelfTest;
import ru.bitcoin.node.rvn.pow.RvnPowException;
import ru.bitcoin.node.rvn.app.config.RvnMiningSettings;
import ru.bitcoin.node.rvn.app.config.RvnDataDirectories;

/**
 * Separate RVN application; does not component-scan Bitcoin modules.
 */
@SpringBootApplication
public class RavencoinNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(RavencoinNodeApplication.class, args);
    }

    @Bean
    RvnDataDirectories rvnDataDirectories(
            @Value("${ravencoin.data-directory:" + RvnDataDirectories.DEFAULT_ROOT + "}") String directory,
            @Value("${ravencoin.storage.hot-directory:" + RvnDataDirectories.DEFAULT_HOT_ROOT + "}") String hotDirectory,
            @Value("${ravencoin.validation.native-replay-enabled:false}") boolean nativeReplay,
            @Value("${ravencoin.validation.canonical-asset-replay-enabled:false}") boolean canonicalAssets,
            @Value("${ravencoin.sync.enabled:false}") boolean sync,
            @Value("${ravencoin.sync.blocks-enabled:false}") boolean blocks,
            @Value("${ravencoin.mining.enabled:false}") boolean mining,
            @Value("${ravencoin.mining.cuda.enabled:false}") boolean cuda) {
        if (mining && (!sync || !blocks || !nativeReplay || !canonicalAssets || !cuda))
            throw new IllegalArgumentException("RVN mining requires sync, block downloads, historical asset replay and CUDA");
        if (nativeReplay && (!sync || !blocks))
            throw new IllegalArgumentException("RVN native replay requires header sync and block download");
        if (canonicalAssets && !nativeReplay)
            throw new IllegalArgumentException("RVN canonical asset replay requires native replay");
        if (directory.isBlank() || hotDirectory.isBlank()) throw new IllegalArgumentException("RVN data directories must not be blank");
        var paths = new RvnDataDirectories(java.nio.file.Path.of(directory), java.nio.file.Path.of(hotDirectory));
        if (sync && !paths.root().equals(paths.hotRoot())
                && ((java.nio.file.Files.exists(paths.root().resolve("headers/CURRENT"))
                     && !java.nio.file.Files.exists(paths.headers().resolve("CURRENT")))
                    || (java.nio.file.Files.exists(paths.root().resolve("coins/CURRENT"))
                        && !java.nio.file.Files.exists(paths.coins().resolve("CURRENT")))))
            throw new IllegalStateException("Legacy RVN database found on cold root; migrate it offline or explicitly set RVN_HOT_DIRECTORY to its parent");
        return paths;
    }

    @Bean
    RvnMiningSettings rvnMiningSettings(@Value("${ravencoin.network:mainnet}") String network,
                                       @Value("${ravencoin.mining.address:}") String address) {
        return new RvnMiningSettings(RvnNetwork.parse(network), address);
    }

    @Bean
    RvnKawpowLightEngine rvnKawpowEngine(@Value("${ravencoin.pow.cached-dag-items:1024}") int capacity) {
        return new RvnKawpowLightEngine(capacity);
    }

    @Bean
    RvnJavaPowEngine rvnPowEngine(RvnKawpowLightEngine kawpow) throws RvnPowException {
        return new RvnJavaPowEngine(kawpow);
    }

    @Bean
    ApplicationRunner rvnStartup(@Value("${ravencoin.mining.enabled:false}") boolean mining,
                                 @Value("${ravencoin.sync.enabled:false}") boolean sync,
                                 @Value("${ravencoin.sync.blocks-enabled:false}") boolean blocks,
                                 RvnMiningSettings miningSettings,
                                 @Value("${ravencoin.pow.self-test-enabled:false}") boolean selfTest,
                                 RvnKawpowLightEngine powEngine,
                                 RvnJavaPowEngine allEraEngine,
                                 @Value("${ravencoin.p2p.probe-enabled:false}") boolean probe,
                                 @Value("${ravencoin.network:mainnet}") String networkName,
                                 @Value("${ravencoin.p2p.host:127.0.0.1}") String host,
                                 @Value("${ravencoin.p2p.port:0}") int configuredPort,
                                 @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout,
                                 @Value("${ravencoin.p2p.session-enabled:false}") boolean sessionEnabled,
                                 @Value("${ravencoin.p2p.session-duration-ms:5000}") int sessionDurationMs,
                                 @Value("${ravencoin.p2p.queue-capacity:64}") int queueCapacity) {
        return args -> {
            if (mining) miningSettings.requirePayout();
            if (blocks && !sync)
                throw new IllegalArgumentException("RVN body download requires ravencoin.sync.enabled=true");
            RvnNetwork network = RvnNetwork.parse(networkName);
            if (selfTest) {
                RvnHistoricalPowSelfTest.requireValid(allEraEngine);
                System.out.println("RVN X16R/X16RV2 CPU self-test OK (genesis and Core C vectors)");
                RvnKawpowSelfTest.requireValid(powEngine);
                System.out.println("RVN KawPoW CPU self-test OK (Ravencoin Core known answers)");
            }
            System.out.println("RVN " + network + "; header sync=" + sync + "; P2P probe=" + probe
                    + "; body download=" + blocks + "; payout configured=" + miningSettings.payout().isPresent()
                    + "; mining requested=" + mining + "; complete historical validation pending");
            if (probe && sessionEnabled) throw new IllegalArgumentException("Choose probe or session, not both");
            if (sessionEnabled) {
                if (sessionDurationMs < 100 || sessionDurationMs > 60000)
                    throw new IllegalArgumentException("session-duration-ms must be 100..60000");
                int port = configuredPort == 0 ? network.port() : configuredPort;
                try (RvnPeerSession session = RvnPeerSession.connect(network, host, port, timeout, queueCapacity)) {
                    System.out.println("RVN session connected: peerVersion=" + session.peerVersion());
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(sessionDurationMs);
                    int received = 0;
                    while (System.nanoTime() < deadline) {
                        long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        if (remaining <= 0) break;
                        var message = session.poll(Math.min(remaining, 250));
                        if (message != null) received++;
                    }
                    System.out.println("RVN session finished; queued messages consumed=" + received);
                }
            }
            if (probe) {
                int port = configuredPort == 0 ? network.port() : configuredPort;
                var result = new RvnPeerProbe().probe(network, host, port, timeout);
                System.out.println("RVN handshake OK: " + result.host() + ":" + result.port() + " peerVersion=" + result.peerVersion());
            }
        };
    }
}

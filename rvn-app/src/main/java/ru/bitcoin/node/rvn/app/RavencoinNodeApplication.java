package ru.bitcoin.node.rvn.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.p2p.RvnPeerProbe;
import ru.bitcoin.node.rvn.p2p.RvnPeerSession;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;

/**
 * Separate RVN application; does not component-scan Bitcoin modules.
 */
@SpringBootApplication
public class RavencoinNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(RavencoinNodeApplication.class, args);
    }

    @Bean
    ApplicationRunner rvnStartup(@Value("${ravencoin.mining.enabled:false}") boolean mining,
                                 @Value("${ravencoin.p2p.probe-enabled:false}") boolean probe,
                                 @Value("${ravencoin.network:mainnet}") String networkName,
                                 @Value("${ravencoin.p2p.host:127.0.0.1}") String host,
                                 @Value("${ravencoin.p2p.port:0}") int configuredPort,
                                 @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout,
                                 @Value("${ravencoin.p2p.session-enabled:false}") boolean sessionEnabled,
                                 @Value("${ravencoin.p2p.session-duration-ms:5000}") int sessionDurationMs,
                                 @Value("${ravencoin.p2p.queue-capacity:64}") int queueCapacity) {
        return args -> {
            if (mining) throw new IllegalStateException("RVN mining NOT implemented; refusing to claim it is active");
            RvnNetwork network = RvnNetwork.parse(networkName);
            System.out.println("RVN stage 2: " + network + "; P2P probe=" + probe + "; chain/KawPoW mining NOT implemented");
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

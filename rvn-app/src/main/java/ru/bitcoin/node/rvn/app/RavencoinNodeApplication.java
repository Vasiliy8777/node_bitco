package ru.bitcoin.node.rvn.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.p2p.RvnPeerProbe;

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
                                 @Value("${ravencoin.p2p.timeout-ms:5000}") int timeout) {
        return args -> {
            if (mining) throw new IllegalStateException("RVN mining NOT implemented; refusing to claim it is active");
            RvnNetwork network = RvnNetwork.parse(networkName);
            System.out.println("RVN stage 2: " + network + "; P2P probe=" + probe + "; chain/KawPoW mining NOT implemented");
            if (probe) {
                int port = configuredPort == 0 ? network.port() : configuredPort;
                var result = new RvnPeerProbe().probe(network, host, port, timeout);
                System.out.println("RVN handshake OK: " + result.host() + ":" + result.port() + " peerVersion=" + result.peerVersion());
            }
        };
    }
}

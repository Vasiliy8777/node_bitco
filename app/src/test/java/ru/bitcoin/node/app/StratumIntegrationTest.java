package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.stratum.StratumServer;
import java.io.EOFException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class StratumIntegrationTest {
    @TempDir Path directory;

    @Test void authenticatesChecksSharesPublishesBlockAndReplacesStaleJobs() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var ready = new AtomicBoolean(true);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var backend = new StratumMiningBackend(validation, relay, parameters, () -> 1_800_000_000L,
                        ready::get, new byte[]{0x51}, 4_000_000, new FeeRate(0));
                try (var server = new StratumServer(new InetSocketAddress("127.0.0.1", 0), backend, "miner", "secret",
                        new BigDecimal("0.0000000001"), 4); var client = new StratumWireMiner(server.port())) {
                    assertEquals(25, error(client.call("mining.submit", List.of())));
                    assertEquals(Map.of("version-rolling", false), client.call("mining.configure", List.of(List.of("version-rolling"), Map.of())).get("result"));
                    client.subscribe();
                    assertEquals(8, client.extraNonce2Size);
                    assertEquals(24, error(client.call("mining.submit", List.of())));
                    assertEquals(false, client.call("mining.authorize", List.of("miner.test", "wrong")).get("result"));
                    assertEquals(true, client.call("mining.authorize", List.of("miner.test", "secret")).get("result"));
                    var job = client.job();
                    assertEquals(true, job.get(8));
                    var ordinaryShare = client.solve(job, false);
                    assertEquals(true, client.call("mining.submit", ordinaryShare.params()).get("result"));
                    assertEquals(22, error(client.call("mining.submit", ordinaryShare.params())));
                    var malformed = new ArrayList<Object>(ordinaryShare.params()); malformed.set(2, "00");
                    assertEquals(20, error(client.call("mining.submit", malformed)));
                    var foreignWorker = new ArrayList<Object>(ordinaryShare.params()); foreignWorker.set(0, "miner.other");
                    assertEquals(24, error(client.call("mining.submit", foreignWorker)));
                    var solution = client.solve(job, true);
                    assertEquals(true, client.call("mining.submit", solution.params()).get("result"));
                    assertEquals(solution.header().hash(), validation.activeTip().hash());
                    assertEquals(21, error(client.call("mining.submit", ordinaryShare.params())));
                    var nextJob = client.job();
                    assertNotEquals(job.getFirst(), nextJob.getFirst());
                    assertEquals(true, nextJob.get(8));
                    assertEquals(2, server.statistics().acceptedShares());
                    assertEquals(1, server.statistics().submittedBlocks());
                    try (var second = new StratumWireMiner(server.port())) {
                        second.subscribe();
                        assertNotEquals(client.extraNonce, second.extraNonce);
                    }
                    ready.set(false);
                    assertThrows(EOFException.class, client::job);
                }
            }
        }
    }

    @Test void configuredServerStartsWithoutRpcAndWaitsForSynchronization() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("stratum-test", Map.of(
                    "bitcoin.data-directory", directory.toString(), "bitcoin.network", "regtest", "bitcoin.stratum.enabled", "true",
                    "bitcoin.stratum.port", "0", "bitcoin.stratum.password", "secret", "bitcoin.mining.payout-script", "51")));
            context.register(ru.bitcoin.node.app.config.NetworkConfiguration.class, ru.bitcoin.node.app.config.NodeConfiguration.class,
                    ru.bitcoin.node.app.config.StratumConfiguration.class);
            context.refresh();
            assertTrue(context.getBean(StratumServer.class).port() > 0);
            assertTrue(context.getBeansOfType(ru.bitcoin.node.app.rpc.NodeRpcServer.class).isEmpty());
        }
    }

    @Test void boundsConnectionsAndRequestsAndClosesActiveClients() throws Exception {
        var backend = new ru.bitcoin.node.stratum.job.MiningBackend() {
            public Optional<ru.bitcoin.node.stratum.job.MiningWork> work() { return Optional.empty(); }
            public boolean isCurrent(ru.bitcoin.node.common.types.Hash256 parent) { return false; }
            public boolean submit(ru.bitcoin.node.protocol.block.Block block) { throw new AssertionError("No work issued"); }
            public long currentTimeSeconds() { return 1_800_000_000L; }
        };
        try (var server = new StratumServer(new InetSocketAddress("127.0.0.1", 0), backend,
                "miner", "secret", BigDecimal.ONE, 1)) {
            try (var first = new StratumWireMiner(server.port())) {
                first.subscribe();
                try (var excess = new java.net.Socket("127.0.0.1", server.port())) {
                    excess.setSoTimeout(5000);
                    assertEquals(-1, excess.getInputStream().read());
                }
                first.socket.getOutputStream().write("x".repeat(16_385).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertThrows(EOFException.class, first::job);
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (server.statistics().connections() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, server.statistics().connections());
            try (var replacement = new StratumWireMiner(server.port())) {
                replacement.subscribe();
                server.close();
                assertThrows(EOFException.class, replacement::job);
                assertEquals(0, server.statistics().connections());
            }
        }
    }

    private static int error(Map<?, ?> response) { return ((Number)((List<?>) response.get("error")).getFirst()).intValue(); }
}

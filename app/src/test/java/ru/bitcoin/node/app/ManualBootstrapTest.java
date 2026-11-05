package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.*;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ManualBootstrapTest {
    @TempDir Path directory;
    @Test void manualOnlyStartupWaitsForAHandshakeAndCanBeCancelled() throws Exception {
        try (var silent = new java.net.ServerSocket(0); var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-wait", Map.of(
                    "bitcoin.network", "regtest", "bitcoin.data-directory", directory.resolve("waiting").toString(),
                    "bitcoin.p2p.listen", false, "bitcoin.p2p.maxconnections", 0, "bitcoin.p2p.v2-transport", false,
                    "bitcoin.p2p.manual-peers", "127.0.0.1:" + silent.getLocalPort())));
            context.register(NetworkConfiguration.class, NodeConfiguration.class); context.refresh();
            var lifecycle = context.getBean(NodeLifecycleService.class);
            var failure = new AtomicReference<Throwable>();
            var worker = Thread.ofPlatform().daemon().start(() -> { try { lifecycle.start(); } catch (Throwable error) { failure.set(error); } });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (lifecycle.state() != NodeLifecycleState.SYNCHRONIZING_HEADERS && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(NodeLifecycleState.SYNCHRONIZING_HEADERS, lifecycle.state());
                assertFalse(lifecycle.isMiningReady());
            } finally { lifecycle.close(); worker.join(2000); }
            assertFalse(worker.isAlive()); assertNull(failure.get());
            assertEquals(NodeLifecycleState.STOPPED, lifecycle.state());
        }
    }

    @Test void manualOnlyConnectionDownloadsHeadersAndBlockWithoutAutomaticSlots() throws Exception {
        var network = NetworkParametersRegistry.regtest();
        try (var sourceDb = new RocksDbDatabase(directory.resolve("source"));
             var source = new NodeValidationService(sourceDb, network, () -> System.currentTimeMillis() / 1000, new Mempool());
             var remotePeers = new PeerManager(); var server = new BitcoinServer(network, remotePeers);
             var context = new AnnotationConfigApplicationContext()) {
            var mining = new MiningController(source, null, network, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
            var block = MiningRpcTest.mineTemplate(mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
            source.processBlock(block);
            remotePeers.addPeerListener(peer -> peer.addMessageListener((remote, message) -> {
                try {
                if (message.command().equals("getheaders")) {
                    var request = GetHeadersMessageCodec.decode(message.payload());
                    remote.send(BitcoinMessages.headers(new HeadersMessage(request.locatorHashes().contains(block.hash()) ? List.of() : List.of(block.header()))));
                } else if (message.command().equals("getdata")) {
                    for (var vector : BitcoinMessages.decodeGetData(message).inventory())
                        if (vector.hash().equals(block.hash())) remote.send(BitcoinMessages.block(new BlockMessage(block)));
                }
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }));
            server.start(0, 1);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("manual-bootstrap", Map.of(
                    "bitcoin.network", "regtest", "bitcoin.data-directory", directory.resolve("node").toString(),
                    "bitcoin.p2p.listen", false, "bitcoin.p2p.maxconnections", 0, "bitcoin.p2p.v2-transport", false,
                    "bitcoin.p2p.manual-peers", "127.0.0.1:" + server.localPort())));
            context.register(NetworkConfiguration.class, NodeConfiguration.class); context.refresh();
            var lifecycle = context.getBean(NodeLifecycleService.class);
            var validation = context.getBean(NodeValidationService.class);
            var failure = new AtomicReference<Throwable>();
            var worker = Thread.ofPlatform().daemon().start(() -> { try { lifecycle.start(); } catch (Throwable error) { failure.set(error); } });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while ((!validation.activeTip().hash().equals(block.hash()) || lifecycle.state() != NodeLifecycleState.RUNNING || !lifecycle.isMiningReady())
                        && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
                assertNull(failure.get()); assertEquals(block.hash(), validation.activeTip().hash());
                assertEquals(NodeLifecycleState.RUNNING, lifecycle.state()); assertTrue(lifecycle.isMiningReady());
                var peers = context.getBean(PeerManager.class); assertEquals(1, peers.readyPeers().size());
                var peer = peers.readyPeers().getFirst(); assertTrue(peer.isManualConnection());
                assertTrue(peers.hasRole(peer, PeerConnectionRole.MANUAL)); assertFalse(peers.hasChainSyncProtection(peer));
                assertTrue(peer.permissions().isEmpty());
            } finally { lifecycle.close(); worker.interrupt(); worker.join(3000); }
            assertFalse(worker.isAlive());
        }
    }
}

package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcConnectionLimitTest {
    @TempDir Path directory;

    @Test void limitsIdleSocketsAndAcceptsRequestsAfterOneDisconnects() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager();
             var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool())) {
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var mining = new MiningController(validation, relay, parameters, () -> false, new byte[]{0x51}, 4_000_000, new FeeRate(1));
                try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1", 0), "test", "test-password",
                        mining, validation, relay, sync, () -> false, peers)) {
                    var sockets = new ArrayList<Socket>();
                    try {
                        for (int i = 0; i < 16; i++) sockets.add(new Socket("127.0.0.1", rpc.port()));
                        try (var excess = new Socket("127.0.0.1", rpc.port())) {
                            excess.setSoTimeout(3000);
                            try { assertEquals(-1, excess.getInputStream().read()); }
                            catch (SocketException closed) { /* A reset is also an enforced connection rejection. */ }
                        }
                        sockets.removeFirst().close();
                        try (var client = HttpClient.newHttpClient()) {
                            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + rpc.port()))
                                    .timeout(Duration.ofSeconds(3))
                                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes()))
                                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":1,\"method\":\"uptime\",\"params\":[]}")).build();
                            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                            while (true) {
                                try {
                                    assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                                    break;
                                } catch (java.io.IOException pendingClose) {
                                    if (System.nanoTime() >= deadline) throw pendingClose;
                                    Thread.sleep(10);
                                }
                            }
                        }
                    } finally { for (var socket : sockets) socket.close(); }
                }
            }
        }
    }
}

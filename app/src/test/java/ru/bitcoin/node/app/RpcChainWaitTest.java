package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcChainWaitTest {
    @TempDir Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void timeoutAndAlreadySatisfiedConditionsReturnActualTip() throws Exception {
        try (var f = new Fixture(directory)) {
            var tip = f.tip();
            for (var request : List.of(new Case("waitfornewblock", List.of(1)),
                    new Case("waitfornewblock", Map.of("current_tip", "33".repeat(32))),
                    new Case("waitforblock", List.of(tip.get("hash"))),
                    new Case("waitforblock", List.of("33".repeat(32), 1)),
                    new Case("waitforblockheight", List.of(-1)),
                    new Case("waitforblockheight", List.of(1, 1)),
                    new Case("waitforblockheight", Map.of("args", List.of(0), "timeout", 1))))
                assertEquals(tip, f.result(request.method(), request.params()));
        }
    }

    @Test void allThreeWaitersWakeOnlyAfterCommittedTipChange() throws Exception {
        try (var f = new Fixture(directory)) {
            var block = MiningRpcTest.mineTemplate(f.mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
            var waits = List.of(f.async("waitfornewblock", List.of()),
                    f.async("waitforblock", List.of(block.hash().toDisplayHex())), f.async("waitforblockheight", List.of(1)));
            f.awaitWaiters(3);
            f.validation.wakeChainTipWaiters();
            for (var wait : waits) assertThrows(TimeoutException.class, () -> wait.get(30, TimeUnit.MILLISECONDS));
            assertEquals(ru.bitcoin.node.chain.BlockProcessingResult.CONNECTED, f.validation.processBlock(block));
            for (var wait : waits) assertEquals(f.tip(), result(wait.get(5, TimeUnit.SECONDS)));
        }
    }

    @Test void reorgToLowerHeightWakesNewBlockButHistoricalHashDoesNotSatisfyBlockWait() throws Exception {
        try (var f = new Fixture(directory)) {
            var genesis = f.tip();
            var block = MiningRpcTest.mineTemplate(f.mining.getBlockTemplate(Map.of("rules", List.of("segwit"))));
            f.validation.processBlock(block);
            assertEquals(f.tip(), f.result("waitforblock", List.of(genesis.get("hash"), 1)));
            var wait = f.async("waitfornewblock", Map.of("current_tip", block.hash().toDisplayHex()));
            f.awaitWaiters(1); f.validation.invalidateBlock(block.hash());
            assertEquals(genesis, result(wait.get(5, TimeUnit.SECONDS)));
        }
    }

    @Test void shutdownReturnsCurrentTipToIndefiniteWaiter() throws Exception {
        try (var f = new Fixture(directory)) {
            var tip = f.tip(); var wait = f.async("waitforblockheight", List.of(1));
            f.awaitWaiters(1); f.closeRpc();
            assertEquals(tip, result(wait.get(5, TimeUnit.SECONDS)));
        }
    }

    @Test void shutdownAlsoDrainsRequestRegisteredBeforeEnteringWait() throws Exception {
        try (var f = new Fixture(directory, true)) {
            var tip = f.tip(); var index = f.validation.activeTip();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closing = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Tip read not released");
                return index;
            }).when(f.validation).activeTip();
            org.mockito.Mockito.doAnswer(invocation -> { closing.countDown(); return invocation.callRealMethod(); })
                    .when(f.validation).wakeChainTipWaiters();
            var wait = f.async("waitfornewblock", List.of());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var shutdown = CompletableFuture.runAsync(f::closeRpc);
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(tip, result(wait.get(5, TimeUnit.SECONDS))); shutdown.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    private record Case(String method, Object params) {}
    private static Object result(HttpResponse<String> response) {
        var body = JSON.readValue(response.body(), Map.class); assertNull(body.get("error"), body.toString()); return body.get("result");
    }
    private static final class Fixture implements AutoCloseable {
        final RocksDbDatabase db;
        final NodeValidationService validation;
        final MiningController mining;
        final NodeRpcServer rpc;
        final HttpClient client = HttpClient.newHttpClient();
        final URI uri;
        boolean rpcClosed;
        Fixture(Path directory) throws Exception { this(directory, false); }
        Fixture(Path directory, boolean spy) throws Exception {
            var parameters = NetworkParametersRegistry.regtest(); db = new RocksDbDatabase(directory);
            var service = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            validation = spy ? org.mockito.Mockito.spy(service) : service;
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            mining = new MiningController(validation, null, parameters, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(0));
            rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1", 0), "test", "test-password", mining, validation, null, sync, () -> true);
            uri = URI.create("http://127.0.0.1:" + rpc.port());
        }
        Map<String, Object> tip() { var tip = validation.activeTip(); return Map.of("hash", tip.hash().toDisplayHex(), "height", (int) tip.height()); }
        HttpRequest request(String method, Object params) {
            return HttpRequest.newBuilder(uri).header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("id", 1, "method", method, "params", params)))).build();
        }
        Object result(String method, Object params) throws Exception { return RpcChainWaitTest.result(client.send(request(method, params), HttpResponse.BodyHandlers.ofString())); }
        CompletableFuture<HttpResponse<String>> async(String method, Object params) { return client.sendAsync(request(method, params), HttpResponse.BodyHandlers.ofString()); }
        void awaitWaiters(int count) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                var commands = (List<?>) ((Map<?, ?>) result("getrpcinfo", List.of())).get("active_commands");
                if (commands.stream().filter(value -> ((Map<?, ?>) value).get("method").toString().startsWith("waitfor")).count() >= count) return;
                Thread.sleep(5);
            }
            fail("RPC waiters did not start");
        }
        void closeRpc() { if (!rpcClosed) { rpcClosed = true; rpc.close(); } }
        @Override public void close() { closeRpc(); client.close(); validation.close(); db.close(); }
    }
}

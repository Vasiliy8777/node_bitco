package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent native Core wire comparison for the same 6000-header/minwork scenario. */
@EnabledIfSystemProperty(named = "bitcoin.core.binary", matches = ".+")
class BitcoinCorePresyncWireTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpClient http;
    private URI endpoint;

    @Test void coreRequiresTheSamePresyncAndRedownloadWithoutPersistingTheFirstPass() throws Exception {
        int rpcPort = freePort(), p2pPort = freePort();
        while (p2pPort == rpcPort) p2pPort = freePort();
        var data = Files.createDirectory(directory.resolve("core"));
        endpoint = URI.create("http://127.0.0.1:" + rpcPort);
        var core = new ProcessBuilder(System.getProperty("bitcoin.core.binary"), "-regtest", "-datadir=" + data,
                "-server", "-listen=1", "-bind=127.0.0.1:" + p2pPort, "-port=" + p2pPort,
                "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort,
                "-minimumchainwork=2328", "-assumevalid=0")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try (var client = HttpClient.newHttpClient(); var peers = new PeerManager()) {
            http = client;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { rpc("getblockcount"); break; }
                catch (Exception failure) {
                    if (!core.isAlive() || System.nanoTime() > deadline) throw failure;
                    Thread.sleep(100);
                }
            }
            int version = ((Number) ((Map<?, ?>) rpc("getnetworkinfo")).get("version")).intValue();
            assertTrue(version >= 320100 && version < 330000);
            rpc("setmocktime", 1_800_000_000L);
            var parameters = NetworkParametersRegistry.regtest();
            var chain = chain(parameters);
            var requests = new LinkedBlockingQueue<BitcoinMessage>();
            var peer = new BitcoinClient(parameters).connectManaged("127.0.0.1", p2pPort, 0, PeerConnectionRole.FULL_RELAY);
            peer.addMessageListener((source, message) -> { if (message.command().equals("getheaders")) requests.add(message); });
            peers.add(peer, PeerConnectionRole.FULL_RELAY);
            for (int batch = 0; batch < 3; batch++) {
                peer.send(BitcoinMessages.headers(new HeadersMessage(chain.subList(batch * 2000, (batch + 1) * 2000))));
                var expected = batch == 2 ? parameters.genesisBlockHash() : chain.get((batch + 1) * 2000 - 1).hash();
                var received = takeLocator(requests);
                // Core may have started its own initial request during handshake.
                if (batch == 0 && received.equals(parameters.genesisBlockHash())) received = takeLocator(requests);
                assertEquals(expected, received);
                assertEquals(0, ((Number) ((Map<?, ?>) rpc("getblockchaininfo")).get("headers")).intValue());
            }
            for (int batch = 0; batch < 3; batch++) {
                peer.send(BitcoinMessages.headers(new HeadersMessage(chain.subList(batch * 2000, (batch + 1) * 2000))));
                if (batch < 2) {
                    assertEquals(chain.get((batch + 1) * 2000 - 1).hash(), takeLocator(requests));
                    assertEquals(0, ((Number) ((Map<?, ?>) rpc("getblockchaininfo")).get("headers")).intValue());
                }
            }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (((Number) ((Map<?, ?>) rpc("getblockchaininfo")).get("headers")).intValue() != 6000
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var info = (Map<?, ?>) rpc("getblockchaininfo");
            assertEquals(6000, ((Number) info.get("headers")).intValue());
            assertEquals(0, ((Number) info.get("blocks")).intValue());
            assertEquals(6000, ((Number) ((Map<?, ?>) rpc("getblockheader", chain.getLast().hash().toDisplayHex())).get("height")).intValue());
            assertEquals(chain.getLast().hash(), takeLocator(requests), "Completed redownload resumes ordinary pagination");
            peer.send(BitcoinMessages.headers(new HeadersMessage(List.of())));
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS), "Empty response must stop pagination");
            rpc("stop"); assertTrue(core.waitFor(15, TimeUnit.SECONDS));
        } finally {
            if (core.isAlive()) {
                try (var shutdown = HttpClient.newHttpClient()) { http = shutdown; rpc("stop"); }
                catch (Exception ignored) { }
                if (!core.waitFor(15, TimeUnit.SECONDS)) { core.destroyForcibly(); core.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }
    private Object rpc(String method, Object... params) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(5))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id", 1, "method", method, "params", Arrays.asList(params))))).build();
        var reply = json.readValue(http.send(request, HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        if (reply.get("error") != null) throw new IllegalStateException(method + ": " + reply.get("error"));
        return reply.get("result");
    }
    private static Hash256 takeLocator(BlockingQueue<BitcoinMessage> requests) throws Exception {
        var message = requests.poll(15, TimeUnit.SECONDS); assertNotNull(message, "Core must request the next presync/redownload headers");
        return GetHeadersMessageCodec.decode(message.payload()).locatorHashes().getFirst();
    }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static List<BlockHeader> chain(NetworkParameters parameters) {
        var result = new ArrayList<BlockHeader>(); var previous = GenesisBlockFactory.create(parameters).header();
        for (int height = 0; height < 6000; height++) {
            BlockHeader header;
            for (long nonce = 0;; nonce++) {
                header = new BlockHeader(4, previous.hash(), new Hash256(new byte[32]),
                        new UInt32(previous.timestamp().value() + 1), new UInt32(0x207fffffL), new UInt32(nonce));
                if (ProofOfWork.isValid(header, parameters)) break;
            }
            result.add(header); previous = header;
        }
        return result;
    }
}

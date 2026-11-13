package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real Core timer/permission decisions with mock time, two independent wire peers. */
@EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
class BitcoinCoreHeadersTimingWireTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private HttpClient http; private URI endpoint;
    @org.junit.jupiter.api.Test
    void coreChainworkFinalProbeUsesNullLocatorAtGenesis() throws Exception {
        int rpcPort = freePort();
        var data = Files.createDirectory(directory.resolve("core"));
        endpoint = URI.create("http://127.0.0.1:" + rpcPort);
        var core = new ProcessBuilder(System.getProperty("bitcoin.core.binary"), "-regtest", "-datadir=" + data,
                "-server", "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort, "-minimumchainwork=0")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try (var client = HttpClient.newHttpClient(); var peers = new PeerManager();
             var server = new BitcoinServer(NetworkParametersRegistry.regtest(), peers)) {
            http = client;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { rpc("getblockcount"); break; }
                catch (Exception error) { if (!core.isAlive() || System.nanoTime() > end) throw error; Thread.sleep(100); }
            }
            long now = 1_800_000_000L;
            rpc("setmocktime", now);
            var requests = new LinkedBlockingQueue<GetHeadersMessage>();
            var pongs = new LinkedBlockingQueue<Long>();
            peers.addPeerListener(peer -> peer.addMessageListener((source, message) -> {
                if (message.command().equals("getheaders")) requests.add(
                        ru.bitcoin.node.p2p.codec.GetHeadersMessageCodec.decode(message.payload()));
                if (message.command().equals("pong")) pongs.add(BitcoinMessages.decodePong(message).nonce());
            }));
            server.start(0, 0);
            rpc("addconnection", "127.0.0.1:" + server.localPort(), "outbound-full-relay", false);
            var initial = requests.poll(5, TimeUnit.SECONDS); assertNotNull(initial);
            assertEquals(List.of(rpc("getblockhash", 0)), initial.locatorHashes().stream().map(hash -> hash.toDisplayHex()).toList());
            var peer = peers.readyPeers().getFirst();
            tick(peer, pongs, now);
            for (long time = now + 300; time <= now + 1200; time += 300) tick(peer, pongs, time);
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS), "Chainwork probe fired at or before its strict boundary");
            tick(peer, pongs, now + 1201);
            var finalProbe = requests.poll(5, TimeUnit.SECONDS); assertNotNull(finalProbe);
            assertTrue(finalProbe.locatorHashes().isEmpty(), "Core's benchmark parent is null at genesis");
            assertEquals("00".repeat(32), finalProbe.stopHash().toDisplayHex());
            tick(peer, pongs, now + 1321); assertTrue(peer.isReady());
            rpc("setmocktime", now + 1322);
            await(() -> !peer.isReady(), "Core did not evict the lagging source after the final response deadline");
            rpc("stop"); assertTrue(core.waitFor(15, TimeUnit.SECONDS));
        } finally {
            if (core.isAlive()) {
                try (var cleanup = HttpClient.newHttpClient()) { http = cleanup; rpc("stop"); } catch (Exception ignored) { }
                if (!core.waitFor(15, TimeUnit.SECONDS)) { core.destroyForcibly(); core.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void actualCoreDeadlineRequiresAlternativeAndResetsNoban(boolean noBan, boolean delayVerack) throws Exception {
        int rpcPort=freePort(),p2pPort=freePort();while(p2pPort==rpcPort)p2pPort=freePort();
        var data=Files.createDirectory(directory.resolve("core"));endpoint=URI.create("http://127.0.0.1:"+rpcPort);
        var command=new ArrayList<>(List.of(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,"-server",
                "-listen=1","-bind=127.0.0.1:"+p2pPort,"-port="+p2pPort,"-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0",
                "-rpcuser=test","-rpcpassword=test-password","-rpcport="+rpcPort,"-minimumchainwork=0"));
        if(noBan)command.add("-whitelist=noban@127.0.0.1");
        var core=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try(var client=HttpClient.newHttpClient();var local=new PeerManager();var alternatives=new PeerManager();
            var server=new BitcoinServer(NetworkParametersRegistry.regtest(),alternatives);
            var delayed = new VersionOnlyAlternative()) {
            http=client;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{rpc("getblockcount");break;}catch(Exception error){if(!core.isAlive()||System.nanoTime()>end)throw error;Thread.sleep(100);}}
            long now=1_800_000_000L;rpc("setmocktime",now);
            var info=(Map<?,?>)rpc("getblockchaininfo");
            long best=((Number)((Map<?,?>)rpc("getblockheader",info.get("bestblockhash"))).get("time")).longValue();
            long deadline=HeadersSyncTimeoutPolicy.initialDeadline(now*1_000_000L,now,best,600);
            long before=deadline/1_000_000L,after=before+1;
            var requests=new AtomicInteger();var alternativeRequests=new AtomicInteger();var pongs=new LinkedBlockingQueue<Long>();
            alternatives.addPeerListener(peer->peer.addMessageListener((source,message)->{if(message.command().equals("getheaders"))alternativeRequests.incrementAndGet();}));
            var peer=new BitcoinClient(NetworkParametersRegistry.regtest()).connectManaged("127.0.0.1",p2pPort,0,PeerConnectionRole.FULL_RELAY);
            peer.addMessageListener((source,message)->{if(message.command().equals("getheaders"))requests.incrementAndGet();
                if(message.command().equals("pong"))pongs.add(BitcoinMessages.decodePong(message).nonce());});
            local.add(peer);await(()->requests.get()>=1,"Core did not start headers sync");
            var nativePeer=(Map<?,?>)((List<?>)rpc("getpeerinfo")).getFirst();
            assertEquals(noBan,((List<?>)nativePeer.get("permissions")).contains("noban"));
            // Keep transport activity fresh while advancing well beyond 20 minutes.
            for(long clock=now+300;clock<after;clock+=300)tick(peer,pongs,clock);
            tick(peer,pongs,after);assertTrue(peer.isReady(),"No alternative: Core must keep the source after deadline");
            // Freeze just before the fractional-microsecond deadline, then add a preferred outbound source.
            tick(peer,pongs,before);
            if (delayVerack) {
                delayed.start();
                rpc("addnode", "127.0.0.1:" + delayed.port(), "onetry", false);
                delayed.versionAccepted.get(5, TimeUnit.SECONDS);
                assertTrue(alternatives.readyPeers().isEmpty());
            } else {
                server.start(0,0);rpc("addnode","127.0.0.1:"+server.localPort(),"onetry",false);
                await(()->!alternatives.readyPeers().isEmpty(),"Alternative handshake did not complete");
            }
            Thread.sleep(250);assertTrue(peer.isReady(),"Core expired headers before its computed deadline");
            assertEquals(1,requests.get());rpc("setmocktime",after);
            if(noBan){await(()->requests.get()>=2||alternativeRequests.get()>=1,"Core did not reset the NoBan sync state");assertTrue(peer.isReady());}
            else await(()->!peer.isReady(),"Core did not disconnect the expired source with a preferred alternative");
            rpc("stop");assertTrue(core.waitFor(15,TimeUnit.SECONDS));
        } finally {
            if(core.isAlive()) {
                try(var cleanup=HttpClient.newHttpClient()){http=cleanup;rpc("stop");}catch(Exception ignored){}
                if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
            }
        }
    }
    /** Replies to VERSION but deliberately never sends VERACK. */
    private static final class VersionOnlyAlternative implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private volatile Socket socket;
        private CompletableFuture<Void> task;
        final CompletableFuture<Void> versionAccepted = new CompletableFuture<>();
        VersionOnlyAlternative() throws java.io.IOException { }
        int port() { return listener.getLocalPort(); }
        void start() {
            task = CompletableFuture.runAsync(() -> {
                try (var accepted = listener.accept()) {
                    socket = accepted; accepted.setSoTimeout(30000);
                    var parameters = NetworkParametersRegistry.regtest();
                    var reader = new ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader(
                            new ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder(parameters));
                    var encoder = new ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder(parameters);
                    var input = new java.io.BufferedInputStream(accepted.getInputStream());
                    assertEquals("version", reader.read(input).orElseThrow().command());
                    var version = new VersionMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES, 1_800_000_000L,
                            NetworkAddress.unspecified(), NetworkAddress.unspecified(), 0x12345678L,
                            "/version-only/", 0, true);
                    accepted.getOutputStream().write(encoder.encode(BitcoinMessages.version(version)));
                    accepted.getOutputStream().flush();
                    while (!reader.read(input).orElseThrow().command().equals("verack")) { }
                    versionAccepted.complete(null);
                    while (reader.read(input).isPresent()) { } // Core may send GETADDR before our VERACK.
                } catch (Exception failure) {
                    versionAccepted.completeExceptionally(failure);
                    throw new CompletionException(failure);
                }
            });
        }
        @Override public void close() throws Exception {
            listener.close();
            if (socket != null) socket.close();
            if (task != null) task.get(5, TimeUnit.SECONDS);
        }
    }
    private void tick(Peer peer,BlockingQueue<Long> pongs,long clock) throws Exception {
        rpc("setmocktime",clock);peer.send(BitcoinMessages.ping(new PingMessage(clock)));
        Long nonce=pongs.poll(3,TimeUnit.SECONDS);assertEquals(Long.valueOf(clock),nonce,"Reference transport stopped responding");
    }
    private static void await(java.util.function.BooleanSupplier condition,String message) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(condition.getAsBoolean(),message);
    }
    private Object rpc(String method,Object...params) throws Exception {
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Authorization","Basic "+Base64.getEncoder()
                .encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("id",1,"method",method,"params",Arrays.asList(params))))).build();
        var reply=json.readValue(http.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
        if (reply.get("error") != null) throw new java.io.IOException("Core RPC " + method + ": " + reply.get("error"));
        return reply.get("result");
    }
    private static int freePort() throws Exception {try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
}

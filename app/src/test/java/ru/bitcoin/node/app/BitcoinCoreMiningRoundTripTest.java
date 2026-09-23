package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.*;
import ru.bitcoin.node.app.rpc.MiningController;
import ru.bitcoin.node.app.service.*;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.net.ServerSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Launches an isolated Core regtest process; never connects to a user's data directory. */
class BitcoinCoreMiningRoundTripTest {
    @TempDir Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private Path cli;
    private Path coreData;
    private int rpcPort;

    @Test
    @EnabledIfSystemProperty(named = "bitcoin.core.binary", matches = ".+")
    void minesTemplateWithRelayedTransactionAndCoreAcceptsBlockThenReconnects() throws Exception {
        Path binary = Path.of(System.getProperty("bitcoin.core.binary"));
        cli = binary.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "bitcoin-cli.exe" : "bitcoin-cli");
        coreData = Files.createDirectory(directory.resolve("core"));
        rpcPort = port();
        int p2pPort = port();
        var process = new ProcessBuilder(binary.toString(), "-datadir=" + coreData, "-regtest", "-server",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort,
                "-port=" + p2pPort, "-bind=127.0.0.1:" + p2pPort, "-connect=0", "-dnsseed=0", "-discover=0",
                "-listenonion=0", "-natpmp=0", "-fallbackfee=0.0001", "-whitelist=noban@127.0.0.1", "-printtoconsole=1")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try {
            await(() -> {
                try { command("getblockcount"); return true; } catch (Exception ignored) { return false; }
            }, Duration.ofSeconds(20));
            command("createwallet", "mining");
            String address = command("getnewaddress").strip();
            command("generatetoaddress", "101", address);
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("core-round-trip", Map.of(
                        "bitcoin.data-directory", directory.resolve("java-node").toString(), "bitcoin.network", "regtest",
                        "bitcoin.p2p.peers", "127.0.0.1:" + p2pPort)));
                context.register(NetworkConfiguration.class, NodeConfiguration.class);
                context.refresh();
                var lifecycle = context.getBean(NodeLifecycleService.class);
                var validation = context.getBean(NodeValidationService.class);
                try (var runner = new NodeLifecycleRunner(lifecycle)) {
                    runner.start();
                    await(() -> lifecycle.isMiningReady() && validation.activeTip().height() == 101, Duration.ofSeconds(45));
                    String destination = command("getnewaddress").strip();
                    String txid = command("sendtoaddress", destination, "1").strip();
                    await(() -> validation.mempoolEntries().stream().anyMatch(entry -> entry.transaction().txId().toDisplayHex().equals(txid)), Duration.ofSeconds(15));
                    var mining = new MiningController(validation, context.getBean(NodeRelayService.class), NetworkParametersRegistry.regtest(),
                            lifecycle::isMiningReady, new byte[]{0x51}, 3_996_000, new FeeRate(0));
                    var template = mining.getBlockTemplate(Map.of("rules", List.of("segwit")));
                    assertFalse(((List<?>)template.get("transactions")).isEmpty());
                    var mined = MiningRpcTest.mineTemplate(template);
                    assertNull(mining.submitBlock(HexFormat.of().formatHex(BlockSerializer.serialize(mined))));
                    await(() -> {
                        try { return command("getbestblockhash").strip().equals(mined.hash().toDisplayHex()); }
                        catch (Exception exception) { return false; }
                    }, Duration.ofSeconds(15));
                    assertTrue(validation.mempoolEntries().isEmpty());
                    command("generatetoaddress", "1", address);
                    await(() -> validation.activeTip().height() == 103 && lifecycle.isMiningReady(), Duration.ofSeconds(15));
                    for (var peer : context.getBean(PeerManager.class).readyPeers()) peer.close();
                    command("generatetoaddress", "1", address);
                    await(() -> validation.activeTip().height() == 104 && lifecycle.isMiningReady(), Duration.ofSeconds(20));
                    assertEquals(command("getbestblockhash").strip(), validation.activeTip().hash().toDisplayHex());
                    assertTrue(lifecycle.failure().isEmpty());
                    // Mine a second wallet transaction through the actual Stratum TCP interface.
                    String stratumTxid = command("sendtoaddress", command("getnewaddress").strip(), "1").strip();
                    await(() -> validation.mempoolEntries().stream().anyMatch(entry -> entry.transaction().txId().toDisplayHex().equals(stratumTxid)), Duration.ofSeconds(15));
                    var backend = new StratumMiningBackend(validation, context.getBean(NodeRelayService.class),
                            NetworkParametersRegistry.regtest(), () -> java.time.Instant.now().getEpochSecond(),
                            lifecycle::isMiningReady, new byte[]{0x51}, 3_996_000, new FeeRate(0));
                    try (var stratum = new ru.bitcoin.node.stratum.StratumServer(new java.net.InetSocketAddress("127.0.0.1", 0),
                            backend, "miner", "test-password", new java.math.BigDecimal("0.0000000001"), 4);
                         var client = new StratumWireMiner(stratum.port())) {
                        client.subscribe();
                        assertEquals(true, client.call("mining.authorize", List.of("miner.test", "test-password")).get("result"));
                        var job = client.job();
                        assertFalse(((List<?>)job.get(4)).isEmpty(), "Stratum job must include the wallet transaction");
                        var solution = client.solve(job, true);
                        var accepted = client.call("mining.submit", solution.params());
                        assertNull(accepted.get("error"));
                        assertEquals(true, accepted.get("result"));
                        await(() -> {
                            try { return command("getbestblockhash").strip().equals(solution.header().hash().toDisplayHex()); }
                            catch (Exception exception) { return false; }
                        }, Duration.ofSeconds(15));
                        assertEquals(105, validation.activeTip().height());
                        assertTrue(validation.mempoolEntries().isEmpty());
                        assertEquals(1, stratum.statistics().submittedBlocks());
                    }
                }
            }
        } finally {
            try { command("stop"); } catch (Exception ignored) { }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private String command(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(cli.toString(), "-datadir=" + coreData, "-regtest", "-rpcuser=test",
                "-rpcpassword=test-password", "-rpcport=" + rpcPort));
        command.addAll(List.of(arguments));
        Path outputFile = Files.createTempFile(directory, "core-rpc-", ".txt");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Core RPC timeout"); }
        String output = Files.readString(outputFile);
        if (process.exitValue() != 0) throw new IllegalStateException(output);
        return output;
    }

    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "Condition not reached within " + timeout);
    }
}

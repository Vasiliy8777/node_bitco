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
    private String chainOption = "-regtest";

    @Test
    @EnabledIfSystemProperty(named = "bitcoin.core.binary", matches = ".+")
    void testnet4GenesisMatchesIsolatedCore() throws Exception {
        Path binary = Path.of(System.getProperty("bitcoin.core.binary"));
        cli = binary.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "bitcoin-cli.exe" : "bitcoin-cli");
        coreData = Files.createDirectory(directory.resolve("core-testnet4"));
        chainOption = "-testnet4";
        rpcPort = port();
        var process = new ProcessBuilder(binary.toString(), "-datadir=" + coreData, chainOption, "-server",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort,
                "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core-testnet4.log").toFile()).start();
        try {
            await(() -> {
                try { command("getblockcount"); return true; } catch (Exception ignored) { return false; }
            }, Duration.ofSeconds(20));
            assertEquals("testnet4", JSON.readValue(command("getblockchaininfo"), Map.class).get("chain"));
            var genesis = ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(NetworkParametersRegistry.testnet4());
            assertEquals(genesis.hash().toDisplayHex(), command("getblockhash", "0").strip());
            assertEquals(HexFormat.of().formatHex(BlockSerializer.serialize(genesis)),
                    command("getblock", genesis.hash().toDisplayHex(), "0").strip());
        } finally {
            try { command("stop"); } catch (Exception ignored) { }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

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
                        "bitcoin.p2p.peers", "127.0.0.1:" + p2pPort, "bitcoin.p2p.listen", "false")));
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
                        assertEquals(Map.of("version-rolling", true, "version-rolling.mask", "1fffe000"),
                                client.call("mining.configure", List.of(List.of("version-rolling"),
                                        Map.of("version-rolling.mask", "ffffffff", "version-rolling.min-bit-count", 2))).get("result"));
                        client.subscribe();
                        assertEquals(true, client.call("mining.authorize", List.of("miner.test", "test-password")).get("result"));
                        var job = client.job();
                        assertFalse(((List<?>)job.get(4)).isEmpty(), "Stratum job must include the wallet transaction");
                        var solution = client.solve(job, true, "00006000", 0x1fffe000);
                        assertEquals(0x20006000, solution.header().version());
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

    /** Opt-in end-to-end IBD measurement against an isolated local Core peer. */
    @Test
    @EnabledIfSystemProperty(named = "ibd.benchmark.core", matches = "true")
    void measuresInitialSyncFromCore() throws Exception {
        Path binary = Path.of(System.getProperty("bitcoin.core.binary"));
        cli = binary.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "bitcoin-cli.exe" : "bitcoin-cli");
        coreData = Files.createDirectory(directory.resolve("core-ibd"));
        rpcPort = port();
        int p2pPort = port();
        int count = Integer.getInteger("ibd.benchmark.blocks", 8192);
        assertTrue(count > 0, "ibd.benchmark.blocks must be positive");
        // Regtest enables full block-tree consistency assertions by default.
        // Match public-network defaults for this performance fixture only;
        // consensus validation remains enabled. Set 1 to reproduce the old fixture.
        int checkBlockIndex = Integer.getInteger("ibd.benchmark.core.checkblockindex", 0);
        assertTrue(checkBlockIndex >= 0, "checkblockindex must not be negative");
        System.out.printf(Locale.ROOT, "IBD_BENCH_CONFIG blocks=%d coreCheckBlockIndex=%d%n", count, checkBlockIndex);
        var process = new ProcessBuilder(binary.toString(), "-datadir=" + coreData, "-regtest", "-server",
                "-checkblockindex=" + checkBlockIndex,
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort,
                "-port=" + p2pPort, "-bind=127.0.0.1:" + p2pPort, "-connect=0", "-dnsseed=0", "-discover=0",
                "-listenonion=0", "-natpmp=0", "-whitelist=noban@127.0.0.1")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core-ibd.log").toFile()).start();
        try {
            await(() -> {
                try { command("getblockcount"); return true; } catch (Exception ignored) { return false; }
            }, Duration.ofSeconds(20));
            command("createwallet", "ibd");
            String address = command("getnewaddress").strip();
            for (int offset = 0; offset < count; offset += 512)
                command("generatetoaddress", Integer.toString(Math.min(512, count - offset)), address);
            measureReferenceCoreIbd(binary, p2pPort, count, checkBlockIndex);
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("core-ibd", Map.of(
                        "bitcoin.data-directory", directory.resolve("java-ibd").toString(), "bitcoin.network", "regtest",
                        "bitcoin.p2p.peers", "127.0.0.1:" + p2pPort, "bitcoin.p2p.listen", "false")));
                context.register(NetworkConfiguration.class, NodeConfiguration.class);
                context.refresh();
                var lifecycle = context.getBean(NodeLifecycleService.class);
                var validation = context.getBean(NodeValidationService.class);
                try (var runner = new NodeLifecycleRunner(lifecycle)) {
                    long started = System.nanoTime();
                    runner.start();
                    await(() -> validation.activeTip().height() == count, Duration.ofMinutes(3));
                    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                    System.out.printf(Locale.ROOT, "IBD_CORE_BENCH blocks=%d seconds=%.3f blocks/s=%.1f%n",
                            count, seconds, count / seconds);
                    assertEquals(command("getbestblockhash").strip(), validation.activeTip().hash().toDisplayHex());
                    assertTrue(lifecycle.failure().isEmpty());
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
    private void measureReferenceCoreIbd(Path binary, int sourcePort, int count, int checkBlockIndex) throws Exception {
        Path referenceData = Files.createDirectory(directory.resolve("core-ibd-reference"));
        int referenceRpcPort = port();
        var process = new ProcessBuilder(binary.toString(), "-datadir=" + referenceData, "-regtest", "-server",
                "-checkblockindex=" + checkBlockIndex,
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + referenceRpcPort,
                "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core-ibd-reference.log").toFile()).start();
        try {
            await(() -> {
                try { rpcCommand(referenceData, referenceRpcPort, "getblockcount"); return true; }
                catch (Exception ignored) { return false; }
            }, Duration.ofSeconds(20));
            assertEquals("0", rpcCommand(referenceData, referenceRpcPort, "getblockcount").strip());
            long started = System.nanoTime();
            rpcCommand(referenceData, referenceRpcPort, "addnode", "127.0.0.1:" + sourcePort, "onetry");
            await(() -> {
                try { return Integer.parseInt(rpcCommand(referenceData, referenceRpcPort, "getblockcount").strip()) == count; }
                catch (Exception ignored) { return false; }
            }, Duration.ofMinutes(3));
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "IBD_REFERENCE_CORE_BENCH blocks=%d seconds=%.3f blocks/s=%.1f%n",
                    count, seconds, count / seconds);
            assertEquals(command("getbestblockhash").strip(),
                    rpcCommand(referenceData, referenceRpcPort, "getbestblockhash").strip());
        } finally {
            try { rpcCommand(referenceData, referenceRpcPort, "stop"); } catch (Exception ignored) { }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private String command(String... arguments) throws Exception {
        return rpcCommand(coreData, rpcPort, arguments);
    }

    private String rpcCommand(Path data, int port, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(cli.toString(), "-datadir=" + data, chainOption, "-rpcuser=test",
                "-rpcpassword=test-password", "-rpcport=" + port));
        command.addAll(List.of(arguments));
        Path outputFile = Files.createTempFile(directory, "core-rpc-", ".txt");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        // Bulk regtest generation can exceed 10 s on a busy Windows disk.
        // This is fixture setup; ordinary polling RPCs keep their short bound.
        long timeoutSeconds = arguments.length > 0 && arguments[0].equals("generatetoaddress") ? 60 : 10;
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Core RPC " + arguments[0] + " timed out after " + timeoutSeconds + " s");
        }
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

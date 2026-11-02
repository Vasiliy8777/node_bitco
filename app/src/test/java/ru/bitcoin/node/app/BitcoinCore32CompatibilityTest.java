package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Independent RPC/fee-estimation differential against an isolated Core 32.1 binary. */
class BitcoinCore32CompatibilityTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private Path cli, coreData;
    private int rpcPort;

    @Test
    @EnabledIfSystemProperty(named = "bitcoin.core.binary", matches = ".+")
    void discoveryAndBothFeeEstimatorsMatchColdAndWarmCore32() throws Exception {
        Path binary = Path.of(System.getProperty("bitcoin.core.binary"));
        cli = binary.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "bitcoin-cli.exe" : "bitcoin-cli");
        coreData = Files.createDirectory(directory.resolve("core"));
        try (var socket = new ServerSocket(0)) { rpcPort = socket.getLocalPort(); }
        var core = new ProcessBuilder(binary.toString(), "-regtest", "-datadir=" + coreData,
                "-server", "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort,
                "-spendzeroconfchange=0", "-fallbackfee=0.00002")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { core("getblockcount"); break; }
                catch (Exception e) { if (System.nanoTime() > deadline) throw e; Thread.sleep(100); }
            }
            var version = json.readValue(core("getnetworkinfo"), Map.class);
            org.junit.jupiter.api.Assumptions.assumeTrue(((Number) version.get("version")).intValue() >= 320100);
            var parameters = NetworkParametersRegistry.regtest();
            try (var db = new RocksDbDatabase(directory.resolve("java")); var peers = new PeerManager();
                 var validation = new NodeValidationService(db, parameters, () -> System.currentTimeMillis() / 1000, new Mempool())) {
                var sync = new NodeSyncInfrastructure(db, parameters, () -> System.currentTimeMillis() / 1000);
                try (var relay = new NodeRelayService(validation, sync, peers)) {
                    var mining = new MiningController(validation, relay, parameters, () -> true, new byte[]{0x51}, 4_000_000, new FeeRate(1));
                    try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1", 0), "test", "test-password",
                            mining, validation, relay, sync, () -> true, peers); var client = HttpClient.newHttpClient()) {
                        URI uri = URI.create("http://127.0.0.1:" + rpc.port());
                        var referenceDoc = json.readValue(core("getopenrpcinfo"), Map.class);
                        var javaDoc = (Map<?, ?>) call(client, uri, "getopenrpcinfo", List.of());
                        assertEquals(referenceDoc.get("openrpc"), javaDoc.get("openrpc"));
                        assertEquals(javaDoc, call(client, uri, "rpc.discover", List.of()));
                        assertEquals(call(client, URI.create("http://127.0.0.1:" + rpcPort), "getmempoolfeeratediagram", List.of()),
                                call(client, uri, "getmempoolfeeratediagram", List.of()), "empty mempool includes the origin");
                        compareFees(client, uri, "auto"); compareFees(client, uri, "mempool_policy");
                        core("createwallet", "oracle");
                        String address = core("getnewaddress").strip();
                        core("generatetoaddress", "200", address);
                        importBlocks(validation, 1, 200);
                        for (int round = 0; round < 8; round++) {
                            for (int tx = 0; tx < 8; tx++) {
                                String txid = core("-named", "sendtoaddress", "address=" + address, "amount=0.01", "fee_rate=2").strip();
                                validation.admit(TransactionParser.parse(HexFormat.of().parseHex(core("getrawtransaction", txid).strip())));
                            }
                            core("generatetoaddress", "1", address);
                            importBlocks(validation, 201 + round, 201 + round);
                            compareFees(client, uri, "block_policy");
                            compareFees(client, uri, "mempool_policy");
                            compareFees(client, uri, "auto");
                        }
                        assertNotNull(validation.estimateFee("block_policy", 2, false).satoshisPerKvB());
                        assertEquals("mempool_policy", validation.estimateFee("auto", 2, false).estimator());
                        compareSharedParentCluster(client, uri, validation);
                        core("stop");
                        assertTrue(core.waitFor(15, TimeUnit.SECONDS));
                        var nativeStatistics = new MempoolFeeEstimator();
                        assertTrue(nativeStatistics.load(coreData.resolve("regtest/fees/mempool_policy_estimator.dat"),
                                validation.activeTip().height(), validation.activeTip().hash()));
                        assertEquals(validation.mempoolFeeHealthStatistics(), nativeStatistics.statistics());
                        var nativeBlockPolicy = new BlockPolicyFeeEstimator();
                        assertTrue(nativeBlockPolicy.load(coreData.resolve("regtest/fees/block_policy_estimates.dat")));
                        assertEquals(validation.estimateFee("block_policy", 2, true).satoshisPerKvB(),
                                nativeBlockPolicy.estimate(2, true).satoshisPerKvB());
                    }
                }
            }
        } finally {
            try { core("stop"); } catch (Exception ignored) { }
            if (!core.waitFor(15, TimeUnit.SECONDS)) { core.destroyForcibly(); core.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private void importBlocks(NodeValidationService validation, int first, int last) throws Exception {
        for (int height = first; height <= last; height++) {
            String hash = core("getblockhash", Integer.toString(height)).strip();
            validation.processBlock(BlockParser.parse(HexFormat.of().parseHex(core("getblock", hash, "0").strip())));
        }
    }

    private void compareSharedParentCluster(HttpClient client, URI uri, NodeValidationService validation) throws Exception {
        var coins = json.readValue(core("listunspent"), List.class);
        var coin = (Map<?, ?>) coins.stream().filter(value ->
                ((Number) ((Map<?, ?>) value).get("amount")).doubleValue() > 2).findFirst().orElseThrow();
        var amount = new java.math.BigDecimal(coin.get("amount").toString());
        String firstAddress = core("getnewaddress").strip(), secondAddress = core("getnewaddress").strip();
        var parentOutputs = new LinkedHashMap<String, java.math.BigDecimal>();
        parentOutputs.put(firstAddress, java.math.BigDecimal.ONE);
        var secondAmount = amount.subtract(java.math.BigDecimal.ONE).subtract(new java.math.BigDecimal("0.00000100"));
        parentOutputs.put(secondAddress, secondAmount);
        String parent = broadcastSpend(client, validation, coin.get("txid").toString(), ((Number) coin.get("vout")).intValue(), parentOutputs);
        broadcastSpend(client, validation, parent, 0, Map.of(core("getnewaddress").strip(), new java.math.BigDecimal("0.99997000")));
        broadcastSpend(client, validation, parent, 1, Map.of(core("getnewaddress").strip(), secondAmount.subtract(new java.math.BigDecimal("0.00003200"))));
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var expectedCluster = (Map<?, ?>) call(client, reference, "getmempoolcluster", List.of(parent));
        assertEquals(3, ((Number) expectedCluster.get("txcount")).intValue());
        assertEquals(1, ((List<?>) expectedCluster.get("chunks")).size(), "Core must combine both children with their shared parent");
        assertEquals(expectedCluster, call(client, uri, "getmempoolcluster", Map.of("txid", parent)));
        assertEquals(call(client, reference, "getmempoolfeeratediagram", List.of()),
                call(client, uri, "getmempoolfeeratediagram", List.of()));
        assertEquals(true, ((Map<?, ?>) call(client, uri, "getmempoolinfo", List.of())).get("optimal"));
    }

    private String broadcastSpend(HttpClient client, NodeValidationService validation, String parent, int output, Map<String, java.math.BigDecimal> outputs) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        String raw = (String) call(client, reference, "createrawtransaction", List.of(List.of(Map.of("txid", parent, "vout", output)), outputs));
        var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(raw));
        assertEquals(true, signed.get("complete"));
        String hex = signed.get("hex").toString();
        String txid = (String) call(client, reference, "sendrawtransaction", List.of(hex));
        var transaction = TransactionParser.parse(HexFormat.of().parseHex(hex));
        assertEquals(txid, transaction.txId().toDisplayHex());
        validation.admit(transaction);
        return txid;
    }

    private void compareFees(HttpClient client, URI uri, String estimator) throws Exception {
        for (String mode : List.of("economical", "conservative")) {
            var options = Map.of("fee_rate_estimator", estimator, "verbosity", 2);
            var expected = call(client, URI.create("http://127.0.0.1:" + rpcPort), "estimatesmartfee", List.of(2, mode, options));
            var actual = call(client, uri, "estimatesmartfee", Map.of("conf_target", 2, "estimate_mode", mode, "options", options));
            assertEquals(expected, actual, estimator + "/" + mode);
        }
    }

    private Object call(HttpClient client, URI uri, String method, Object params) throws Exception {
        var request = HttpRequest.newBuilder(uri).header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id", 1, "method", method, "params", params)))).build();
        var response = json.readValue(client.send(request, HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        assertNull(response.get("error"), response.toString()); return response.get("result");
    }

    private String core(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of(cli.toString(), "-regtest", "-datadir=" + coreData,
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(directory, "core-rpc-", ".txt");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Core RPC timeout"); }
        String result = Files.readString(output);
        if (process.exitValue() != 0) throw new IllegalStateException(result);
        return result;
    }
}

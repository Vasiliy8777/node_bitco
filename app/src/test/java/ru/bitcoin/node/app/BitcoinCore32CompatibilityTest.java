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
    void acceptedDustPriorityMatchesCoreWithStandardPolicy() throws Exception {
        Path binary = Path.of(System.getProperty("bitcoin.core.binary"));
        cli = binary.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "bitcoin-cli.exe" : "bitcoin-cli");
        coreData = Files.createDirectory(directory.resolve("strict-dust-core"));
        try (var socket = new ServerSocket(0)) { rpcPort = socket.getLocalPort(); }
        var process = new ProcessBuilder(binary.toString(), "-regtest", "-datadir=" + coreData,
                "-server", "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0",
                "-acceptnonstdtxn=0", "-fallbackfee=0.00002", "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + rpcPort)
                .redirectErrorStream(true).redirectOutput(directory.resolve("strict-dust-core.log").toFile()).start();
        try (var client = HttpClient.newHttpClient()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { core("getblockcount"); break; }
                catch (Exception failure) { if (!process.isAlive() || System.nanoTime() > deadline) throw failure; Thread.sleep(100); }
            }
            core("createwallet", "oracle");
            core("generatetoaddress", "101", core("getnewaddress").strip());
            URI reference = URI.create("http://127.0.0.1:" + rpcPort);
            var coin = (Map<?, ?>) ((List<?>) call(client, reference, "listunspent", List.of())).getFirst();
            var fixture = createSubmissionFixture(client, reference, coin, "0", "0.00010000");
            var template = TransactionParser.parse(HexFormat.of().parseHex(fixture.getFirst()));
            var unsigned = new ru.bitcoin.node.protocol.transaction.Transaction(template.version(), template.inputs(),
                    List.of(template.outputs().getFirst(), new ru.bitcoin.node.protocol.transaction.TxOut(0,
                            template.outputs().getFirst().scriptPubKey())), template.lockTime());
            var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawTransaction(unsigned)));
            assertEquals(true, signed.get("complete"));
            String parentHex = signed.get("hex").toString();
            var parent = TransactionParser.parse(HexFormat.of().parseHex(parentHex));
            var childTemplate = TransactionParser.parse(HexFormat.of().parseHex(fixture.getLast()));
            var inputs = new ArrayList<ru.bitcoin.node.protocol.transaction.TxIn>();
            var previous = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 2; i++) {
                inputs.add(new ru.bitcoin.node.protocol.transaction.TxIn(new ru.bitcoin.node.protocol.transaction.OutPoint(
                        parent.txId(), new ru.bitcoin.node.common.types.UInt32(i)), new byte[0], childTemplate.inputs().getFirst().sequence()));
                var output = parent.outputs().get(i);
                previous.add(Map.of("txid", parent.txId().toDisplayHex(), "vout", i,
                        "scriptPubKey", HexFormat.of().formatHex(output.scriptPubKey()), "amount", java.math.BigDecimal.valueOf(output.value(), 8)));
            }
            var unsignedChild = new ru.bitcoin.node.protocol.transaction.Transaction(childTemplate.version(), inputs,
                    childTemplate.outputs(), childTemplate.lockTime());
            var signedChild = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawTransaction(unsignedChild), previous));
            assertEquals(true, signedChild.get("complete"));
            String childHex = signedChild.get("hex").toString();
            var child = TransactionParser.parse(HexFormat.of().parseHex(childHex));
            assertEquals("success", ((Map<?, ?>) call(client, reference, "submitpackage", List.of(List.of(parentHex, childHex)))).get("package_msg"));
            var pool = new Mempool();
            var funding = parent.inputs().getFirst().previousOutput();
            long amount = new java.math.BigDecimal(coin.get("amount").toString()).movePointRight(8).longValueExact();
            long height = ((Number) call(client, reference, "getblockcount", List.of())).longValue();
            long now = System.currentTimeMillis() / 1000;
            var entry = new ru.bitcoin.node.consensus.transaction.UtxoEntry(amount,
                    HexFormat.of().parseHex(coin.get("scriptPubKey").toString()),
                    height - ((Number) coin.get("confirmations")).longValue() + 1, true);
            assertEquals(2, pool.admitPackage(List.of(parent, child), new MempoolValidationContext(height + 1, now, h -> now - 1),
                    point -> point.equals(funding) ? Optional.of(entry) : Optional.empty()).size());
            var before = pool.detailedSnapshot();
            var priorities = pool.prioritisedTransactions();
            var referencePool = call(client, reference, "getrawmempool", List.of(true));
            var referencePriorities = call(client, reference, "getprioritisedtransactions", List.of());
            for (long delta : new long[]{0, 1, -1}) {
                var error = (Map<?, ?>) callResponse(client, reference, "prioritisetransaction", List.of(parent.txId().toDisplayHex(), 0, delta)).get("error");
                assertNotNull(error, "Strict Core must reject even a zero delta on accepted dust");
                assertEquals(-8, ((Number) error.get("code")).intValue());
                var local = assertThrows(MempoolAdmissionException.class, () -> pool.prioritise(parent.txId(), delta));
                assertEquals(error.get("message"), local.getMessage());
                assertEquals(before, pool.detailedSnapshot());
                assertEquals(priorities, pool.prioritisedTransactions());
                assertEquals(referencePool, call(client, reference, "getrawmempool", List.of(true)));
                assertEquals(referencePriorities, call(client, reference, "getprioritisedtransactions", List.of()));
            }
            for (long delta : new long[]{2, -1}) {
                assertEquals(true, call(client, reference, "prioritisetransaction", List.of(child.txId().toDisplayHex(), 0, delta)));
                pool.prioritise(child.txId(), delta);
            }
            var changed = (Map<?, ?>) ((Map<?, ?>) call(client, reference, "getprioritisedtransactions", List.of())).get(child.txId().toDisplayHex());
            assertEquals(((Number) changed.get("fee_delta")).longValue(), pool.prioritisedTransactions().get(child.txId()).longValue());
            assertEquals(((Number) changed.get("modified_fee")).longValue(), pool.find(child.txId()).orElseThrow().modifiedFee());
        } finally {
            if (process.isAlive()) {
                try { core("stop"); } catch (Exception ignored) { }
                if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }

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
                        String inventoryPath = System.getProperty("bitcoin.core.rpc.inventory");
                        if (inventoryPath != null) {
                            var fullReferenceDoc = json.readValue(core("getopenrpcinfo", "true"), Map.class);
                            var referenceNames = rpcNames(fullReferenceDoc);
                            var implementedNames = rpcNames(javaDoc);
                            var missing = new TreeSet<>(referenceNames); missing.removeAll(implementedNames);
                            var additional = new TreeSet<>(implementedNames); additional.removeAll(referenceNames);
                            var missingByCategory = new TreeMap<String, SortedSet<String>>();
                            for (Object entry : (List<?>) fullReferenceDoc.get("methods")) {
                                var method = (Map<?, ?>) entry;
                                String name = (String) method.get("name");
                                if (missing.contains(name)) missingByCategory.computeIfAbsent(
                                        String.valueOf(method.get("x-bitcoin-category")), ignored -> new TreeSet<>()).add(name);
                            }
                            assertTrue(additional.isEmpty(), "Local method names absent even from Core's hidden catalog: " + additional);
                            Path output = Path.of(inventoryPath);
                            Files.createDirectories(output.toAbsolutePath().getParent());
                            Files.writeString(output, json.writeValueAsString(Map.of(
                                    "referenceVersion", version.get("version"),
                                    "scope", "RPC method names including hidden and wallet methods; schemas and behavior are not implied",
                                    "referenceMethods", referenceNames, "implementedMethods", implementedNames,
                                    "missingMethods", missing, "additionalMethods", additional,
                                    "missingByCategory", missingByCategory)));
                        }
                        assertEquals(javaDoc, call(client, uri, "rpc.discover", List.of()));
                        assertEquals(call(client, URI.create("http://127.0.0.1:" + rpcPort), "getmempoolfeeratediagram", List.of()),
                                call(client, uri, "getmempoolfeeratediagram", List.of()), "empty mempool includes the origin");
                        compareFees(client, uri, "auto"); compareFees(client, uri, "mempool_policy");
                        core("createwallet", "oracle");
                        String address = core("getnewaddress").strip();
                        core("generatetoaddress", "200", address);
                        importBlocks(validation, 1, 200);
                        compareChainWaitRpc(client, uri);
                        for (int round = 0; round < 8; round++) {
                            for (int tx = 0; tx < 8; tx++) {
                                String txid = core("-named", "sendtoaddress", "address=" + address, "amount=0.01", "fee_rate=2").strip();
                                validation.admit(TransactionParser.parse(HexFormat.of().parseHex(core("getrawtransaction", txid).strip())));
                            }
                            String waitingTip = validation.activeTip().hash().toDisplayHex();
                            var referenceWait = round == 0 ? chainWaitAsync(client, URI.create("http://127.0.0.1:" + rpcPort), waitingTip) : null;
                            var localWait = round == 0 ? chainWaitAsync(client, uri, waitingTip) : null;
                            core("generatetoaddress", "1", address);
                            importBlocks(validation, 201 + round, 201 + round);
                            if (round == 0) {
                                var expectedWait = json.readValue(referenceWait.get(10, TimeUnit.SECONDS).body(), Map.class);
                                var actualWait = json.readValue(localWait.get(10, TimeUnit.SECONDS).body(), Map.class);
                                assertNull(expectedWait.get("error")); assertEquals(expectedWait.get("result"), actualWait.get("result"));
                                assertEquals(201, ((Map<?, ?>) expectedWait.get("result")).get("height"));
                            }
                            compareFees(client, uri, "block_policy");
                            compareFees(client, uri, "mempool_policy");
                            compareFees(client, uri, "auto");
                        }
                        assertNotNull(validation.estimateFee("block_policy", 2, false).satoshisPerKvB());
                        assertEquals("mempool_policy", validation.estimateFee("auto", 2, false).estimator());
                        compareSharedParentCluster(client, uri, validation);
                        compareOversizedReplacement(client, uri, validation);
                        compareEphemeralDustFees(client, uri, validation);
                        comparePackageProbeFeeModes(client, uri);
                        compareGeneralPackageProbes(client, uri);
                        compareKnownSingleProbes(client, uri);
                        comparePartialSubmissions(client, uri);
                        compareTrucStates(client, uri);
                        compareEqualFeeMiningOrder(client, uri, validation);
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

    private static SortedSet<String> rpcNames(Map<?, ?> document) {
        var names = new TreeSet<String>();
        for (Object method : (List<?>) document.get("methods")) names.add((String) ((Map<?, ?>) method).get("name"));
        return names;
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

    private void compareOversizedReplacement(HttpClient client, URI uri, NodeValidationService validation) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var coins = ((List<?>) call(client, reference, "listunspent", List.of())).stream()
                .map(value -> (Map<?, ?>) value)
                .filter(coin -> new java.math.BigDecimal(coin.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .limit(3).toList();
        assertEquals(3, coins.size());
        String destination = core("getnewaddress").strip();
        String script = ((Map<?, ?>) call(client, reference, "getaddressinfo", List.of(destination))).get("scriptPubKey").toString();
        var fee = new java.math.BigDecimal("0.00001000");
        var originalCoin = coins.getFirst();
        broadcastSpend(client, validation, originalCoin.get("txid").toString(), ((Number) originalCoin.get("vout")).intValue(),
                Map.of(destination, new java.math.BigDecimal(originalCoin.get("amount").toString()).subtract(fee)));
        var inputs = new ArrayList<Map<String, Object>>();
        var previousOutputs = new ArrayList<Map<String, Object>>();
        inputs.add(Map.of("txid", originalCoin.get("txid"), "vout", originalCoin.get("vout")));
        previousOutputs.add(Map.of("txid", originalCoin.get("txid"), "vout", originalCoin.get("vout"),
                "amount", originalCoin.get("amount"), "scriptPubKey", originalCoin.get("scriptPubKey")));
        var amount = new java.math.BigDecimal(originalCoin.get("amount").toString());
        for (int branch = 1; branch <= 2; branch++) {
            var coin = coins.get(branch); String parent = coin.get("txid").toString();
            int output = ((Number) coin.get("vout")).intValue();
            var value = new java.math.BigDecimal(coin.get("amount").toString());
            for (int depth = 0; depth < 32; depth++) {
                value = value.subtract(fee);
                parent = broadcastSpend(client, validation, parent, output, Map.of(destination, value)); output = 0;
            }
            inputs.add(Map.of("txid", parent, "vout", output));
            previousOutputs.add(Map.of("txid", parent, "vout", output, "amount", value, "scriptPubKey", script));
            amount = amount.add(value);
        }
        String raw = (String) call(client, reference, "createrawtransaction", List.of(inputs,
                Map.of(destination, amount.subtract(new java.math.BigDecimal("0.00100000")))));
        var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(raw, previousOutputs));
        assertEquals(true, signed.get("complete"));
        var params = List.of(List.of(signed.get("hex")));
        var expected = (Map<?, ?>) ((List<?>) call(client, reference, "testmempoolaccept", params)).getFirst();
        var before = call(client, uri, "getrawmempool", List.of());
        var actual = (Map<?, ?>) ((List<?>) call(client, uri, "testmempoolaccept", params)).getFirst();
        assertEquals(false, expected.get("allowed")); assertEquals("too-large-cluster", expected.get("reject-reason"));
        assertEquals(expected.get("allowed"), actual.get("allowed"));
        assertEquals(expected.get("reject-reason"), actual.get("reject-reason"));
        assertEquals(before, call(client, uri, "getrawmempool", List.of()), "Rejected replacement must not mutate the pool");
    }

    private void compareEphemeralDustFees(HttpClient client, URI uri, NodeValidationService validation) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var coin = ((List<?>) call(client, reference, "listunspent", List.of())).stream()
                .map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .findFirst().orElseThrow();
        var amount = new java.math.BigDecimal(coin.get("amount").toString());
        var expectedPool = call(client, reference, "getrawmempool", List.of());
        var actualPool = call(client, uri, "getrawmempool", List.of());
        for (long baseFee : new long[]{1}) {
            String regularAddress = core("getnewaddress").strip();
            String dustAddress = core("getnewaddress").strip();
            var regularAmount = amount.subtract(java.math.BigDecimal.valueOf(baseFee, 8));
            var outputs = new LinkedHashMap<String, java.math.BigDecimal>();
            outputs.put(regularAddress, regularAmount); outputs.put(dustAddress, java.math.BigDecimal.ZERO);
            var rawParent = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", coin.get("txid"), "vout", coin.get("vout"))), outputs));
            var signedParent = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawParent));
            assertEquals(true, signedParent.get("complete"));
            String parentHex = signedParent.get("hex").toString();
            var parent = TransactionParser.parse(HexFormat.of().parseHex(parentHex));
            String parentId = parent.txId().toDisplayHex();
            var previous = new ArrayList<Map<String, Object>>();
            var addresses = List.of(regularAddress, dustAddress);
            for (int index = 0; index < 2; index++) {
                var info = (Map<?, ?>) call(client, reference, "getaddressinfo", List.of(addresses.get(index)));
                previous.add(Map.of("txid", parentId, "vout", index, "scriptPubKey", info.get("scriptPubKey"),
                        "amount", index == 0 ? regularAmount : java.math.BigDecimal.ZERO));
            }
            var rawChild = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", parentId, "vout", 0), Map.of("txid", parentId, "vout", 1)),
                    Map.of(core("getnewaddress").strip(), amount.subtract(new java.math.BigDecimal("0.00010000")))));
            var signedChild = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawChild, previous));
            assertEquals(true, signedChild.get("complete"));
            String childHex = signedChild.get("hex").toString();
            if (baseFee != 0) {
                assertEquals(true, call(client, reference, "prioritisetransaction", List.of(parentId, 0, -baseFee)));
                assertEquals(true, call(client, uri, "prioritisetransaction", List.of(parentId, 0, -baseFee)));
                var priority = (Map<?, ?>) ((Map<?, ?>) call(client, reference, "getprioritisedtransactions", List.of())).get(parentId);
                assertEquals(-baseFee, ((Number) priority.get("fee_delta")).longValue());
                assertEquals(-baseFee, validation.prioritisedTransactions().get(parent.txId()).longValue());
            }
            var expected = (Map<?, ?>) call(client, reference, "submitpackage", List.of(List.of(parentHex, childHex)));
            var expectedParent = (Map<?, ?>) ((Map<?, ?>) expected.get("tx-results")).get(parent.wtxId().toDisplayHex());
            assertNotNull(expectedParent, "Native submitpackage must classify the dust parent");
            assertNotNull(expectedParent.get("error"), expectedParent.toString());
            assertTrue(expectedParent.get("error").toString().contains("dust"), expectedParent.toString());
            var actual = assertThrows(MempoolAdmissionException.class, () -> validation.admitPackage(List.of(parent,
                    TransactionParser.parse(HexFormat.of().parseHex(childHex)))));
            if (baseFee != 0) {
                assertEquals("dust-with-fee", actual.getMessage());
            }
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
    }

    private void comparePackageProbeFeeModes(HttpClient client, URI uri) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var coin = ((List<?>) call(client, reference, "listunspent", List.of())).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .findFirst().orElseThrow();
        var amount = new java.math.BigDecimal(coin.get("amount").toString());
        var expectedPool = call(client, reference, "getrawmempool", List.of());
        var actualPool = call(client, uri, "getrawmempool", List.of());
        for (long parentFee : new long[]{0, 1000}) {
            String destination = core("getnewaddress").strip();
            var script = ((Map<?, ?>) call(client, reference, "getaddressinfo", List.of(destination))).get("scriptPubKey");
            var parentAmount = amount.subtract(java.math.BigDecimal.valueOf(parentFee, 8));
            var rawParent = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", coin.get("txid"), "vout", coin.get("vout"))), Map.of(destination, parentAmount)));
            var signedParent = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawParent));
            assertEquals(true, signedParent.get("complete"));
            String parentHex = signedParent.get("hex").toString();
            String parentId = TransactionParser.parse(HexFormat.of().parseHex(parentHex)).txId().toDisplayHex();
            var rawChild = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", parentId, "vout", 0)), Map.of(core("getnewaddress").strip(),
                            parentAmount.subtract(new java.math.BigDecimal("0.00010000")))));
            var signedChild = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawChild,
                    List.of(Map.of("txid", parentId, "vout", 0, "scriptPubKey", script, "amount", parentAmount))));
            assertEquals(true, signedChild.get("complete"));
            var params = List.of(List.of(parentHex, signedChild.get("hex").toString()));
            var expected = (List<?>) call(client, reference, "testmempoolaccept", params);
            var actual = (List<?>) call(client, uri, "testmempoolaccept", params);
            assertEquals(parentFee != 0, ((Map<?, ?>) expected.getFirst()).get("allowed"));
            assertEquals(((Map<?, ?>) expected.getFirst()).get("allowed"), ((Map<?, ?>) actual.getFirst()).get("allowed"));
            if (parentFee == 0) assertEquals(expected, actual);
            if (parentFee == 0) {
                var valid = TransactionParser.parse(HexFormat.of().parseHex(parentHex));
                var input = valid.inputs().getFirst();
                var witness = new ArrayList<>(input.witness().items());
                witness.getFirst()[10] ^= 1; // Preserve witness shape/DER lengths, invalidate the signature.
                var bad = new ru.bitcoin.node.protocol.transaction.Transaction(valid.version(), List.of(
                        new ru.bitcoin.node.protocol.transaction.TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                                new ru.bitcoin.node.protocol.transaction.Witness(witness))), valid.outputs(), valid.lockTime());
                String badHex = HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(bad));
                var badParams = List.of(List.of(badHex));
                var nativeResult = (Map<?, ?>) ((List<?>) call(client, reference, "testmempoolaccept", badParams)).getFirst();
                var javaResult = (Map<?, ?>) ((List<?>) call(client, uri, "testmempoolaccept", badParams)).getFirst();
                assertEquals("min relay fee not met", nativeResult.get("reject-reason"));
                assertEquals(false, javaResult.get("allowed"));
                assertEquals(nativeResult.get("reject-reason"), javaResult.get("reject-reason"));
                assertEquals(nativeResult, javaResult);
            }
            if (parentFee != 0) for (int i = 0; i < 2; i++) {
                var expectedEntry = (Map<?, ?>) expected.get(i); var actualEntry = (Map<?, ?>) actual.get(i);
                assertEquals(true, actualEntry.get("allowed"));
                assertEquals(expectedEntry.get("vsize"), actualEntry.get("vsize"));
                assertEquals(expectedEntry.get("fees"), actualEntry.get("fees"));
            }
            if (parentFee != 0) {
                String childId = TransactionParser.parse(HexFormat.of().parseHex(signedChild.get("hex").toString())).txId().toDisplayHex();
                var rawFreeChild = call(client, reference, "createrawtransaction", List.of(
                        List.of(Map.of("txid", parentId, "vout", 0)), Map.of(core("getnewaddress").strip(), parentAmount)));
                var signedFreeChild = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawFreeChild,
                        List.of(Map.of("txid", parentId, "vout", 0, "scriptPubKey", script, "amount", parentAmount))));
                assertEquals(true, signedFreeChild.get("complete"));
                var childFeeParams = List.of(List.of(parentHex, signedFreeChild.get("hex").toString()));
                var childFeeExpected = (List<?>) call(client, reference, "testmempoolaccept", childFeeParams);
                assertFalse(((Map<?, ?>) childFeeExpected.getFirst()).containsKey("allowed"));
                assertEquals("min relay fee not met", ((Map<?, ?>) childFeeExpected.get(1)).get("reject-reason"));
                assertEquals(childFeeExpected, call(client, uri, "testmempoolaccept", childFeeParams));
                for (URI endpoint : List.of(reference, uri))
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(parentId, 0, 1000000)));
                // Positive mining delta must not turn a permitted base fee into an excessive RPC fee.
                var capped = List.of(List.of(parentHex, signedChild.get("hex").toString()), new java.math.BigDecimal("0.00020000"));
                assertEquals(call(client, reference, "testmempoolaccept", capped), call(client, uri, "testmempoolaccept", capped));
                for (URI endpoint : List.of(reference, uri))
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(childId, 0, -9000)));
                // A negative delta cannot bypass the child's excessive base-fee check.
                assertEquals(call(client, reference, "testmempoolaccept", capped), call(client, uri, "testmempoolaccept", capped));
                for (URI endpoint : List.of(reference, uri)) {
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(parentId, 0, -1000000)));
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(childId, 0, 9000)));
                }
                long vsize = ((Number) ((Map<?, ?>) expected.getFirst()).get("vsize")).longValue();
                var roundedCap = List.of(List.of(parentHex), java.math.BigDecimal.valueOf(parentFee * 1000 / vsize, 8));
                var roundedExpected = (List<?>) call(client, reference, "testmempoolaccept", roundedCap);
                assertEquals(true, ((Map<?, ?>) roundedExpected.getFirst()).get("allowed"));
                assertEquals(roundedExpected, call(client, uri, "testmempoolaccept", roundedCap));
                for (URI endpoint : List.of(reference, uri))
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(parentId, 0, -1001)));
                var negativeParams = List.of(List.of(parentHex));
                var negativeExpected = (Map<?, ?>) ((List<?>) call(client, reference, "testmempoolaccept", negativeParams)).getFirst();
                var negativeActual = (Map<?, ?>) ((List<?>) call(client, uri, "testmempoolaccept", negativeParams)).getFirst();
                assertEquals("min relay fee not met", negativeExpected.get("reject-reason"));
                assertEquals(false, negativeActual.get("allowed"));
                assertEquals(negativeExpected.get("reject-reason"), negativeActual.get("reject-reason"));
                assertEquals(negativeExpected, negativeActual);
                assertEquals(call(client, reference, "testmempoolaccept", params), call(client, uri, "testmempoolaccept", params));
                for (URI endpoint : List.of(reference, uri))
                    assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(parentId, 0, 1001)));
            }
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
    }

    private void compareKnownSingleProbes(HttpClient client, URI uri) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var expectedPool = (List<?>) call(client, reference, "getrawmempool", List.of());
        var actualPool = call(client, uri, "getrawmempool", List.of());
        String hex = call(client, reference, "getrawtransaction", List.of(expectedPool.getFirst())).toString();
        var tx = TransactionParser.parse(HexFormat.of().parseHex(hex));
        var inputs = new ArrayList<>(tx.inputs());
        var first = inputs.getFirst();
        inputs.set(0, new ru.bitcoin.node.protocol.transaction.TxIn(first.previousOutput(), first.scriptSig(), first.sequence(),
                new ru.bitcoin.node.protocol.transaction.Witness(List.of(new byte[]{0}))));
        var changed = new ru.bitcoin.node.protocol.transaction.Transaction(tx.version(), inputs, tx.outputs(), tx.lockTime());
        assertEquals(tx.txId(), changed.txId()); assertNotEquals(tx.wtxId(), changed.wtxId());
        String changedHex = HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(changed));
        var cases = List.of(hex, changedHex);
        var coin = ((List<?>) call(client, reference, "listunspent", List.of())).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .findFirst().orElseThrow();
        var raw = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", coin.get("txid"), "vout", coin.get("vout"))),
                Map.of(core("getnewaddress").strip(), new java.math.BigDecimal(coin.get("amount").toString()).subtract(new java.math.BigDecimal("0.00010000")))));
        var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(raw));
        assertEquals(true, signed.get("complete"));
        String freshHex = signed.get("hex").toString();
        var reasons = List.of("txn-already-in-mempool", "txn-same-nonwitness-data-in-mempool");
        for (int i = 0; i < cases.size(); i++) {
            var params = List.of(List.of(cases.get(i)));
            var expected = (List<?>) call(client, reference, "testmempoolaccept", params);
            assertEquals(reasons.get(i), ((Map<?, ?>) expected.getFirst()).get("reject-reason"));
            assertEquals(expected, call(client, uri, "testmempoolaccept", params));
            for (var packageTxs : List.of(List.of(cases.get(i), freshHex), List.of(freshHex, cases.get(i)))) {
                var packageParams = List.of(packageTxs);
                var expectedPackage = (List<?>) call(client, reference, "testmempoolaccept", packageParams);
                int failed = packageTxs.getFirst().equals(freshHex) ? 1 : 0;
                assertEquals(reasons.get(i), ((Map<?, ?>) expectedPackage.get(failed)).get("reject-reason"));
                assertFalse(((Map<?, ?>) expectedPackage.get(1 - failed)).containsKey("allowed"));
                assertEquals(expectedPackage, call(client, uri, "testmempoolaccept", packageParams));
            }
            var expectedSubmission = (Map<?, ?>) call(client, reference, "submitpackage", params);
            var actualSubmission = (Map<?, ?>) call(client, uri, "submitpackage", params);
            assertEquals("success", expectedSubmission.get("package_msg"));
            assertEquals(expectedSubmission.get("package_msg"), actualSubmission.get("package_msg"));
            assertEquals(expectedSubmission.get("tx-results"), actualSubmission.get("tx-results"));
            var cappedRepeatParams = List.of(List.of(cases.get(i)), new java.math.BigDecimal("0.00000001"));
            var cappedExpected = (Map<?, ?>) call(client, reference, "submitpackage", cappedRepeatParams);
            var cappedActual = (Map<?, ?>) call(client, uri, "submitpackage", cappedRepeatParams);
            assertEquals("success", cappedExpected.get("package_msg"));
            assertEquals(cappedExpected.get("package_msg"), cappedActual.get("package_msg"));
            assertEquals(cappedExpected.get("tx-results"), cappedActual.get("tx-results"));
            var repeatParams = List.of(cases.get(i), new java.math.BigDecimal("0.00000001"));
            assertEquals(call(client, reference, "sendrawtransaction", repeatParams),
                    call(client, uri, "sendrawtransaction", repeatParams), "known txid uses accepted witness and skips fee cap");
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
    }

    private void compareGeneralPackageProbes(HttpClient client, URI uri) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var coins = ((List<?>) call(client, reference, "listunspent", List.of())).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .limit(2).toList();
        assertEquals(2, coins.size());
        var expectedPool = call(client, reference, "getrawmempool", List.of());
        var actualPool = call(client, uri, "getrawmempool", List.of());
        var chain = new ArrayList<String>();
        Map<?, ?> previous = coins.getFirst();
        for (int i = 0; i < 3; i++) {
            var destination = core("getnewaddress").strip();
            var amount = new java.math.BigDecimal(previous.get("amount").toString()).subtract(new java.math.BigDecimal("0.00010000"));
            var raw = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", previous.get("txid"), "vout", previous.get("vout"))), Map.of(destination, amount)));
            var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet",
                    i == 0 ? List.of(raw) : List.of(raw, List.of(previous)));
            assertEquals(true, signed.get("complete"));
            String hex = signed.get("hex").toString();
            chain.add(hex);
            var txid = TransactionParser.parse(HexFormat.of().parseHex(hex)).txId().toDisplayHex();
            var script = ((Map<?, ?>) call(client, reference, "getaddressinfo", List.of(destination))).get("scriptPubKey");
            previous = Map.of("txid", txid, "vout", 0, "amount", amount, "scriptPubKey", script);
        }
        var other = coins.getLast();
        var rawOther = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", other.get("txid"), "vout", other.get("vout"))),
                Map.of(core("getnewaddress").strip(), new java.math.BigDecimal(other.get("amount").toString()).subtract(new java.math.BigDecimal("0.00010000")))));
        var signedOther = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(rawOther));
        assertEquals(true, signedOther.get("complete"));
        var lowCap = List.of(chain, new java.math.BigDecimal("0.00001000"));
        var lowCapExpected = (List<?>) call(client, reference, "testmempoolaccept", lowCap);
        assertEquals(false, ((Map<?, ?>) lowCapExpected.getFirst()).get("allowed"));
        assertFalse(((Map<?, ?>) lowCapExpected.get(1)).containsKey("allowed"));
        assertFalse(((Map<?, ?>) lowCapExpected.get(2)).containsKey("allowed"));
        assertEquals(lowCapExpected, call(client, uri, "testmempoolaccept", lowCap));
        for (var transactions : List.of(chain, List.of(chain.getFirst(), signedOther.get("hex").toString()))) {
            var expected = (List<?>) call(client, reference, "testmempoolaccept", List.of(transactions));
            var actual = (List<?>) call(client, uri, "testmempoolaccept", List.of(transactions));
            assertEquals(transactions.size(), actual.size());
            for (int i = 0; i < transactions.size(); i++) {
                var expectedEntry = (Map<?, ?>) expected.get(i); var actualEntry = (Map<?, ?>) actual.get(i);
                assertEquals(true, expectedEntry.get("allowed"), expectedEntry.toString());
                for (var field : List.of("allowed", "txid", "wtxid", "vsize", "fees"))
                    assertEquals(expectedEntry.get(field), actualEntry.get(field), field);
            }
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
        var firstCoin = coins.getFirst();
        var conflictRaw = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", firstCoin.get("txid"), "vout", firstCoin.get("vout"))),
                Map.of(core("getnewaddress").strip(), new java.math.BigDecimal(firstCoin.get("amount").toString()).subtract(new java.math.BigDecimal("0.00020000")))));
        var signedConflict = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(conflictRaw));
        assertEquals(true, signedConflict.get("complete"));
        var badPackages = List.of(List.of(chain.getFirst(), chain.getFirst()),
                List.of(chain.get(1), chain.getFirst()), List.of(chain.getFirst(), signedConflict.get("hex").toString()),
                List.of(chain.getFirst(), signedConflict.get("hex").toString(), chain.getLast(), chain.get(1)));
        var reasons = List.of("package-contains-duplicates", "package-not-sorted", "conflict-in-package", "package-not-sorted");
        for (int i = 0; i < badPackages.size(); i++) {
            var params = List.of(badPackages.get(i));
            var expected = (List<?>) call(client, reference, "testmempoolaccept", params);
            var actual = (List<?>) call(client, uri, "testmempoolaccept", params);
            assertEquals(reasons.get(i), ((Map<?, ?>) expected.getFirst()).get("package-error"));
            assertFalse(((Map<?, ?>) expected.getFirst()).containsKey("allowed"));
            assertEquals(expected, actual, reasons.get(i));
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
        String missingHex = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", "33".repeat(32), "vout", 0)),
                Map.of(core("getnewaddress").strip(), new java.math.BigDecimal("0.00100000")))).toString();
        var validParent = TransactionParser.parse(HexFormat.of().parseHex(chain.getFirst()));
        var firstInput = validParent.inputs().getFirst();
        var badWitness = new ArrayList<>(firstInput.witness().items()); badWitness.getFirst()[10] ^= 1;
        var badParent = new ru.bitcoin.node.protocol.transaction.Transaction(validParent.version(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxIn(firstInput.previousOutput(), firstInput.scriptSig(), firstInput.sequence(),
                        new ru.bitcoin.node.protocol.transaction.Witness(badWitness))), validParent.outputs(), validParent.lockTime());
        String badParentHex = HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(badParent));
        String missingChildHex = call(client, reference, "createrawtransaction", List.of(List.of(
                Map.of("txid", validParent.txId().toDisplayHex(), "vout", 0), Map.of("txid", "33".repeat(32), "vout", 0)),
                Map.of(core("getnewaddress").strip(), new java.math.BigDecimal("0.00100000")))).toString();
        // Version/prevout rewrites intentionally invalidate signatures: a later
        // missing input must still win before both shared TRUC checks and scripts.
        var trucChain = new ArrayList<String>();
        ru.bitcoin.node.common.types.Hash256 previousTruc = null;
        for (var rawTx : chain) {
            var parsed = TransactionParser.parse(HexFormat.of().parseHex(rawTx));
            var originalInput = parsed.inputs().getFirst();
            var prevout = previousTruc == null ? originalInput.previousOutput()
                    : new ru.bitcoin.node.protocol.transaction.OutPoint(previousTruc, new ru.bitcoin.node.common.types.UInt32(0));
            var converted = new ru.bitcoin.node.protocol.transaction.Transaction(3, List.of(
                    new ru.bitcoin.node.protocol.transaction.TxIn(prevout, originalInput.scriptSig(), originalInput.sequence(), originalInput.witness())),
                    parsed.outputs(), parsed.lockTime());
            previousTruc = converted.txId();
            trucChain.add(HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(converted)));
        }
        trucChain.add(missingHex);
        var missingPackages = List.of(List.of(missingHex), List.of(missingHex, chain.getFirst()),
                List.of(chain.getFirst(), missingHex), List.of(chain.getFirst(), chain.get(1), missingHex),
                List.of(badParentHex, missingChildHex), trucChain);
        var missingIndices = List.of(0, 0, 1, 2, 1, 3);
        for (int i = 0; i < missingPackages.size(); i++) {
            var params = List.of(missingPackages.get(i));
            var expected = (List<?>) call(client, reference, "testmempoolaccept", params);
            var failed = (Map<?, ?>) expected.get(missingIndices.get(i));
            assertEquals("missing-inputs", failed.get("reject-reason"));
            assertFalse(failed.containsKey("reject-details"));
            assertEquals(expected, call(client, uri, "testmempoolaccept", params));
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
        var singleScriptParams = List.of(List.of(badParentHex));
        assertEquals(call(client, reference, "testmempoolaccept", singleScriptParams),
                call(client, uri, "testmempoolaccept", singleScriptParams));
        // Script failures retain the successful prefix and leave the suffix unfinished.
        for (int failedIndex = 0; failedIndex < chain.size(); failedIndex++) {
            var scriptPackage = new ArrayList<>(chain);
            var parsed = TransactionParser.parse(HexFormat.of().parseHex(chain.get(failedIndex)));
            var input = parsed.inputs().getFirst();
            var witness = new ArrayList<>(input.witness().items()); witness.getFirst()[10] ^= 1;
            var invalid = new ru.bitcoin.node.protocol.transaction.Transaction(parsed.version(), List.of(
                    new ru.bitcoin.node.protocol.transaction.TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                            new ru.bitcoin.node.protocol.transaction.Witness(witness))), parsed.outputs(), parsed.lockTime());
            scriptPackage.set(failedIndex, HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(invalid)));
            for (var cap : List.of(java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.00001000"))) {
                var params = List.of(scriptPackage, cap);
                var expected = (List<?>) call(client, reference, "testmempoolaccept", params);
                var actual = (List<?>) call(client, uri, "testmempoolaccept", params);
                if (cap.signum() == 0) {
                    for (int i = 0; i < failedIndex; i++) assertEquals(true, ((Map<?, ?>) expected.get(i)).get("allowed"));
                    assertTrue(((Map<?, ?>) expected.get(failedIndex)).get("reject-reason").toString().startsWith("mempool-script-verify-flag-failed"));
                    for (int i = failedIndex + 1; i < chain.size(); i++) assertFalse(((Map<?, ?>) expected.get(i)).containsKey("allowed"));
                }
                assertEquals(expected, actual, "script index " + failedIndex + ", cap " + cap);
                assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
                assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
            }
        }
        // Distinguish stack shape, DER, false result, and pubkey-hash failures.
        var scriptChild = TransactionParser.parse(HexFormat.of().parseHex(chain.get(1)));
        var scriptInput = scriptChild.inputs().getFirst();
        var originalWitness = scriptInput.witness().items();
        assertEquals(2, originalWitness.size(), "Fixture must spend P2WPKH");
        byte[] wrongKey = originalWitness.get(1).clone(); wrongKey[10] ^= 1;
        byte[] unknownHash = originalWitness.getFirst().clone(); unknownHash[unknownHash.length - 1] = 0;
        var invalidWitnesses = List.of(List.<byte[]>of(), List.of(originalWitness.getFirst()),
                List.of(new byte[]{0}, originalWitness.get(1)), List.of(new byte[0], originalWitness.get(1)),
                List.of(originalWitness.getFirst(), wrongKey), List.of(unknownHash, originalWitness.get(1)));
        for (var witness : invalidWitnesses) {
            var invalid = new ru.bitcoin.node.protocol.transaction.Transaction(scriptChild.version(), List.of(
                    new ru.bitcoin.node.protocol.transaction.TxIn(scriptInput.previousOutput(), scriptInput.scriptSig(), scriptInput.sequence(),
                            new ru.bitcoin.node.protocol.transaction.Witness(witness))), scriptChild.outputs(), scriptChild.lockTime());
            var scriptPackage = List.of(chain.getFirst(),
                    HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(invalid)), chain.getLast());
            var params = List.of(scriptPackage, java.math.BigDecimal.ZERO);
            assertEquals(call(client, reference, "testmempoolaccept", params), call(client, uri, "testmempoolaccept", params));
            assertEquals(expectedPool, call(client, reference, "getrawmempool", List.of()));
            assertEquals(actualPool, call(client, uri, "getrawmempool", List.of()));
        }
    }

    private java.util.concurrent.CompletableFuture<HttpResponse<String>> chainWaitAsync(HttpClient client, URI uri, String tip) {
        var request = HttpRequest.newBuilder(uri).header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("id", 1, "method", "waitfornewblock", "params", List.of(5000, tip))))).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private record WaitRpcCase(String method, Object params) {}
    private void compareChainWaitRpc(HttpClient client, URI uri) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        var expectedDoc = (Map<?, ?>) call(client, reference, "getopenrpcinfo", List.of());
        var actualDoc = (Map<?, ?>) call(client, uri, "getopenrpcinfo", List.of());
        for (var method : List.of("waitfornewblock", "waitforblock", "waitforblockheight")) {
            var expectedMethod = ((List<?>) expectedDoc.get("methods")).stream().filter(value -> ((Map<?, ?>) value).get("name").equals(method)).findFirst().orElseThrow();
            var actualMethod = ((List<?>) actualDoc.get("methods")).stream().filter(value -> ((Map<?, ?>) value).get("name").equals(method)).findFirst().orElseThrow();
            assertEquals(expectedMethod, actualMethod, "complete OpenRPC method " + method);
            assertEquals(call(client, reference, "help", List.of(method)), call(client, uri, "help", List.of(method)));
        }
        String tip = call(client, reference, "getbestblockhash", List.of()).toString();
        String genesis = call(client, reference, "getblockhash", List.of(0)).toString();
        for (var test : List.of(new WaitRpcCase("waitfornewblock", List.of(1)),
                new WaitRpcCase("waitfornewblock", List.of(0, "33".repeat(32))),
                new WaitRpcCase("waitfornewblock", Map.of("current_tip", "33".repeat(32))),
                new WaitRpcCase("waitfornewblock", Arrays.asList(null, "33".repeat(32))),
                new WaitRpcCase("waitforblock", List.of(tip)), new WaitRpcCase("waitforblock", List.of(genesis, 1)),
                new WaitRpcCase("waitforblock", Map.of("blockhash", tip)),
                new WaitRpcCase("waitforblockheight", List.of(200)), new WaitRpcCase("waitforblockheight", List.of(-1)),
                new WaitRpcCase("waitforblockheight", List.of(201, 1)),
                new WaitRpcCase("waitforblockheight", Map.of("height", 201, "timeout", 1)),
                new WaitRpcCase("waitforblockheight", Map.of("args", List.of(200), "timeout", 1))))
            assertEquals(call(client, reference, test.method(), test.params()), call(client, uri, test.method(), test.params()), test.toString());
        var errors = List.of(new WaitRpcCase("waitforblockheight", List.of()),
                new WaitRpcCase("waitforblockheight", Arrays.asList(null, 1)), new WaitRpcCase("waitforblockheight", List.of("1", 1)),
                new WaitRpcCase("waitforblockheight", List.of(1.25, 1)), new WaitRpcCase("waitforblockheight", List.of(-1.0, 1)),
                new WaitRpcCase("waitforblockheight", List.of(2147483648L, 1)), new WaitRpcCase("waitforblockheight", List.of(1, -1)),
                new WaitRpcCase("waitforblockheight", List.of(1, 1, 1)), new WaitRpcCase("waitforblockheight", Map.of("timeout", 1)),
                new WaitRpcCase("waitforblockheight", Map.of("height", 1, "extra", 1)),
                new WaitRpcCase("waitforblockheight", List.of("bad", "bad")),
                new WaitRpcCase("waitforblockheight", Map.of("args", List.of(1), "height", 0, "timeout", 1)),
                new WaitRpcCase("waitforblockheight", Map.of("args", "bad")),
                new WaitRpcCase("waitforblock", List.of()), new WaitRpcCase("waitforblock", Arrays.asList(null, 1)),
                new WaitRpcCase("waitforblock", List.of(1, 1)), new WaitRpcCase("waitforblock", List.of("zz", 1)),
                new WaitRpcCase("waitforblock", List.of("g".repeat(64), 1)), new WaitRpcCase("waitforblock", List.of("zz", -1)),
                new WaitRpcCase("waitfornewblock", List.of(-1)), new WaitRpcCase("waitfornewblock", List.of(1, "zz")),
                new WaitRpcCase("waitfornewblock", List.of(-1, "zz")), new WaitRpcCase("waitfornewblock", List.of(2147483648L)),
                new WaitRpcCase("waitfornewblock", List.of(1.25)), new WaitRpcCase("waitfornewblock", List.of(true)));
        for (var test : errors) {
            var expected = callResponse(client, reference, test.method(), test.params()).get("error");
            assertNotNull(expected, test.toString());
            assertEquals(expected, callResponse(client, uri, test.method(), test.params()).get("error"), test.toString());
        }
    }

    private void comparePartialSubmissions(HttpClient client, URI uri) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        call(client, reference, "syncwithvalidationinterfacequeue", List.of());
        var coins = ((List<?>) call(client, reference, "listunspent", List.of())).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .limit(4).toList();
        assertEquals(4, coins.size());
        var feeFixture = createSubmissionFixture(client, reference, coins.get(3), "0.00010000", "0.00010000");
        var feeParent = TransactionParser.parse(HexFormat.of().parseHex(feeFixture.getFirst()));
        long feeSize = ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(feeParent);
        // Smallest integral sat/kvB rate whose ceil(rate*vsize/1000) pays 10000 sat.
        long firstAllowedRate = (9999L * 1000 / feeSize) + 1;
        var feeId = feeParent.txId().toDisplayHex();
        for (var endpoint : List.of(reference, uri))
            assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(feeId, 0, -1000)));
        var rejectedCap = List.of(feeFixture.getFirst(), java.math.BigDecimal.valueOf(firstAllowedRate - 1, 8));
        var rejected = callResponse(client, reference, "sendrawtransaction", rejectedCap).get("error");
        assertNotNull(rejected);
        assertEquals(rejected, callResponse(client, uri, "sendrawtransaction", rejectedCap).get("error"), "negative fee delta must not reduce client base-fee cap");
        for (var endpoint : List.of(reference, uri)) {
            assertFalse(((List<?>) call(client, endpoint, "getrawmempool", List.of())).contains(feeId));
            assertEquals(true, call(client, endpoint, "prioritisetransaction", List.of(feeId, 0, 1_001_000)));
        }
        var acceptedCap = List.of(feeFixture.getFirst(), java.math.BigDecimal.valueOf(firstAllowedRate, 8));
        assertEquals(feeId, call(client, reference, "sendrawtransaction", acceptedCap));
        assertEquals(feeId, call(client, uri, "sendrawtransaction", acceptedCap), "ceil cap uses base fee despite positive fee delta");
        var pack = createSubmissionFixture(client, reference, coins.getFirst(), "0.00010000", "0.00010000");
        String priorityId = "00".repeat(32);
        var invalidPriorities = new ArrayList<Object>();
        invalidPriorities.add(Arrays.asList(null, 0, 0));
        invalidPriorities.add(List.of(1, 0, 0));
        invalidPriorities.add(List.of(priorityId, "0", true));
        invalidPriorities.add(List.of("ff", "0", true));
        invalidPriorities.add(Map.of("txid", priorityId, "fee_delta", new java.math.BigDecimal("0.5")));
        invalidPriorities.add(Map.of("txid", priorityId, "dummy", "0", "fee_delta", true));
        invalidPriorities.add(Map.of("txid", 1, "dummy", "0", "fee_delta", true));
        for (var delta : Arrays.asList(null, true, "1", List.of(), Map.of(), new java.math.BigDecimal("0.5"),
                new java.math.BigDecimal("-0.5"), new java.math.BigDecimal("1.0"), new java.math.BigDecimal("1E+2"),
                new java.math.BigInteger("9223372036854775808"), new java.math.BigInteger("-9223372036854775809")))
            invalidPriorities.add(Arrays.asList(priorityId, 0, delta));
        for (var dummy : Arrays.asList(true, "0", List.of(), Map.of(), new java.math.BigDecimal("0.5"),
                new java.math.BigDecimal("-0.5"), 1, -1))
            invalidPriorities.add(List.of(priorityId, dummy, 0));
        invalidPriorities.add(List.of(priorityId, new java.math.BigDecimal("0.5"), new java.math.BigDecimal("0.5")));
        for (var hash : List.of("", "ff", "zz".repeat(32), "é".repeat(32))) {
            invalidPriorities.add(List.of(hash, 0, 0));
            invalidPriorities.add(List.of(hash, new java.math.BigDecimal("0.5"), new java.math.BigDecimal("0.5")));
        }
        var priorityBeforeReference = call(client, reference, "getprioritisedtransactions", List.of());
        var priorityBeforeLocal = call(client, uri, "getprioritisedtransactions", List.of());
        for (var params : invalidPriorities) {
            var expected = callResponse(client, reference, "prioritisetransaction", params).get("error");
            assertNotNull(expected, "Invalid priority fixture: " + params);
            assertEquals(expected, callResponse(client, uri, "prioritisetransaction", params).get("error"), "priority argument order/types " + params);
        }
        assertEquals(priorityBeforeReference, call(client, reference, "getprioritisedtransactions", List.of()));
        assertEquals(priorityBeforeLocal, call(client, uri, "getprioritisedtransactions", List.of()));
        var spend = TransactionParser.parse(HexFormat.of().parseHex(pack.getFirst()));
        var spendInput = spend.inputs().getFirst();
        var missing = new ru.bitcoin.node.protocol.transaction.Transaction(spend.version(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxIn(
                        new ru.bitcoin.node.protocol.transaction.OutPoint(new ru.bitcoin.node.common.types.Hash256(new byte[32]),
                                new ru.bitcoin.node.common.types.UInt32(0)),
                        spendInput.scriptSig(), spendInput.sequence(), spendInput.witness())), spend.outputs(), spend.lockTime());
        var badIndex = new ru.bitcoin.node.protocol.transaction.Transaction(spend.version(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxIn(
                        new ru.bitcoin.node.protocol.transaction.OutPoint(spendInput.previousOutput().transactionId(),
                                new ru.bitcoin.node.common.types.UInt32(ru.bitcoin.node.common.types.UInt32.MAX_VALUE)),
                        spendInput.scriptSig(), spendInput.sequence(), spendInput.witness())), spend.outputs(), spend.lockTime());
        String bestHash = call(client, reference, "getbestblockhash", List.of()).toString();
        var bestBlock = (Map<?, ?>) call(client, reference, "getblock", List.of(bestHash, 1));
        String confirmedId = ((List<?>) bestBlock.get("tx")).getFirst().toString();
        String confirmedHex = call(client, reference, "getrawtransaction", List.of(confirmedId, false, bestHash)).toString();
        for (var raw : List.of(rawTransaction(missing), rawTransaction(badIndex), confirmedHex)) {
            for (var cap : List.of(java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.00000001"), new java.math.BigDecimal("0.1"))) {
                var params = List.of(raw, cap);
                var expected = callResponse(client, reference, "sendrawtransaction", params).get("error");
                assertNotNull(expected);
                assertEquals(expected, callResponse(client, uri, "sendrawtransaction", params).get("error"), "broadcast missing/confirmed classification");
            }
        }
        for (var method : List.of("sendrawtransaction", "testmempoolaccept", "submitpackage")) {
            Object raw = method.equals("sendrawtransaction") ? "zz" : List.of("zz");
            var amounts = Arrays.asList(null, true, List.of(), Map.of(), "", "x", "+1", "01", ".1", "1.", " 0", "0 ",
                    "0.000000001", "-0.000000001", "-0.00000001", "21000000.00000001", "10000000000", "1e9999999999",
                    "1e-9999999999", "1", 1, "1.00000001", "0", "-0", "0.000000010", "1e-8", "0.99999999");
            for (var amount : amounts) {
                var params = Arrays.asList(raw, amount);
                var expected = callResponse(client, reference, method, params).get("error");
                assertNotNull(expected);
                assertEquals(expected, callResponse(client, uri, method, params).get("error"), method + " maxfeerate=" + amount);
                if (!method.equals("testmempoolaccept")) {
                    params = Arrays.asList(raw, 0, amount);
                    expected = callResponse(client, reference, method, params).get("error");
                    assertNotNull(expected);
                    assertEquals(expected, callResponse(client, uri, method, params).get("error"), method + " maxburnamount=" + amount);
                }
            }
        }
        // Valid encoding ensures sendrawtransaction reaches its fee parser;
        // every value here must fail before admission of this signed parent.
        for (var amount : Arrays.asList(true, List.of(), Map.of(), "", "x", "+1", "01", ".1", "1.", " 0", "0 ",
                "0.000000001", "-0.000000001", "-0.00000001", "21000000.00000001", "10000000000", "1e9999999999",
                "1e-9999999999", "1", 1, "1.00000001")) {
            var params = Arrays.asList(pack.getFirst(), amount);
            var expected = callResponse(client, reference, "sendrawtransaction", params).get("error");
            assertNotNull(expected);
            assertEquals(expected, callResponse(client, uri, "sendrawtransaction", params).get("error"), "sendrawtransaction encoded maxfeerate=" + amount);
        }
        for (var method : List.of("testmempoolaccept", "submitpackage")) {
            var beforeReference = call(client, reference, "getrawmempool", List.of());
            var beforeLocal = call(client, uri, "getrawmempool", List.of());
            var malformedParams = new ArrayList<List<?>>();
            for (var value : Arrays.asList(null, 1, true, List.of(), Map.of())) {
                malformedParams.add(List.of(Arrays.asList(value)));
                malformedParams.add(List.of(Arrays.asList(pack.getFirst(), value)));
                malformedParams.add(List.of(Arrays.asList("zz", value)));
                malformedParams.add(List.of(Arrays.asList(value, "zz")));
                malformedParams.add(List.of(Collections.nCopies(26, value)));
            }
            for (var hex : List.of("", "00", "zz", "0100000000010000000000")) {
                malformedParams.add(List.of(List.of(hex)));
                malformedParams.add(List.of(List.of(pack.getFirst(), hex)));
            }
            for (var rawPackage : List.of(List.of(), java.util.Collections.nCopies(26, "00"))) {
                malformedParams.add(List.of(rawPackage));
                malformedParams.add(List.of(rawPackage, "invalid-fee"));
                if (method.equals("submitpackage")) malformedParams.add(List.of(rawPackage, 0, "invalid-burn"));
            }
            for (var params : malformedParams) {
                var expected = callResponse(client, reference, method, params).get("error");
                assertNotNull(expected, method + " fixture must fail: " + params);
                assertEquals(expected, callResponse(client, uri, method, params).get("error"), method + " decoding/order " + params);
            }
            assertEquals(beforeReference, call(client, reference, "getrawmempool", List.of()));
            assertEquals(beforeLocal, call(client, uri, "getrawmempool", List.of()));
        }
        var child = TransactionParser.parse(HexFormat.of().parseHex(pack.getLast()));
        var input = child.inputs().getFirst(); var witness = new ArrayList<>(input.witness().items()); witness.getFirst()[10] ^= 1;
        var invalid = new ru.bitcoin.node.protocol.transaction.Transaction(child.version(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                        new ru.bitcoin.node.protocol.transaction.Witness(witness))), child.outputs(), child.lockTime());
        var invalidPackage = List.of(pack.getFirst(), HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(invalid)));
        var cpfp = createSubmissionFixture(client, reference, coins.get(1), "0.00000000", "0.00010000");
        var capped = createSubmissionFixture(client, reference, coins.get(2), "0.00000100", "0.00010000");
        var retryParent = TransactionParser.parse(HexFormat.of().parseHex(cpfp.getFirst()));
        var retryChild = TransactionParser.parse(HexFormat.of().parseHex(cpfp.getLast()));
        var missingInputs = new ArrayList<>(retryChild.inputs());
        missingInputs.add(new ru.bitcoin.node.protocol.transaction.TxIn(new ru.bitcoin.node.protocol.transaction.OutPoint(
                ru.bitcoin.node.common.types.Hash256.fromDisplayHex("33".repeat(32)), new ru.bitcoin.node.common.types.UInt32(0)),
                new byte[0], ru.bitcoin.node.protocol.transaction.TxIn.FINAL_SEQUENCE));
        var missingRetry = new ru.bitcoin.node.protocol.transaction.Transaction(retryChild.version(), missingInputs, retryChild.outputs(), retryChild.lockTime());
        var zeroRetry = new ru.bitcoin.node.protocol.transaction.Transaction(retryChild.version(), retryChild.inputs(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxOut(retryParent.outputs().getFirst().value(), retryChild.outputs().getFirst().scriptPubKey())), retryChild.lockTime());
        var badRetryParent = corruptSubmissionWitness(cpfp.getFirst());
        var dustRetryParent = new ru.bitcoin.node.protocol.transaction.Transaction(retryParent.version(), retryParent.inputs(), List.of(
                retryParent.outputs().getFirst(), new ru.bitcoin.node.protocol.transaction.TxOut(0, retryParent.outputs().getFirst().scriptPubKey())), retryParent.lockTime());
        var dustChildInput = retryChild.inputs().getFirst();
        var dustRetryChild = new ru.bitcoin.node.protocol.transaction.Transaction(retryChild.version(), List.of(new ru.bitcoin.node.protocol.transaction.TxIn(
                new ru.bitcoin.node.protocol.transaction.OutPoint(dustRetryParent.txId(), new ru.bitcoin.node.common.types.UInt32(0)),
                dustChildInput.scriptSig(), dustChildInput.sequence(), dustChildInput.witness())), retryChild.outputs(), retryChild.lockTime());
        var phaseCases = List.of(List.of(badRetryParent, HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(missingRetry))),
                List.of(badRetryParent, HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(zeroRetry))),
                List.of(badRetryParent, cpfp.getLast()), List.of(cpfp.getFirst(), corruptSubmissionWitness(cpfp.getLast())),
                List.of(HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(dustRetryParent)),
                        HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(dustRetryChild))));
        var referenceBeforeRetry = call(client, reference, "getrawmempool", List.of());
        var localBeforeRetry = call(client, uri, "getrawmempool", List.of());
        for (int i = 0; i < phaseCases.size(); i++) {
            var params = List.of(phaseCases.get(i));
            var expected = (Map<?, ?>) call(client, reference, "submitpackage", params);
            assertEquals(i == 4 ? "unspent-dust" : "transaction failed", expected.get("package_msg"));
            assertEquals(expected, call(client, uri, "submitpackage", params), "shared submission phase " + i);
            assertEquals(referenceBeforeRetry, call(client, reference, "getrawmempool", List.of()));
            assertEquals(localBeforeRetry, call(client, uri, "getrawmempool", List.of()));
        }
        var badTopology = List.of(List.of(pack.getFirst(), cpfp.getFirst()));
        var topologyError = callResponse(client, reference, "submitpackage", badTopology).get("error");
        assertNotNull(topologyError);
        assertEquals(topologyError, callResponse(client, uri, "submitpackage", badTopology).get("error"));
        var burnInput = TransactionParser.parse(HexFormat.of().parseHex(pack.getFirst())).inputs().getFirst();
        for (byte opcode : new byte[]{0x6a, (byte) 0xff}) {
            var burn = new ru.bitcoin.node.protocol.transaction.Transaction(2, List.of(burnInput),
                    List.of(new ru.bitcoin.node.protocol.transaction.TxOut(100, new byte[]{opcode})), new ru.bitcoin.node.common.types.UInt32(0));
            var hex = HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(burn));
            var params = List.of(List.of(hex, "not-hex"));
            var expected = callResponse(client, reference, "submitpackage", params).get("error");
            assertNotNull(expected);
            assertEquals(expected, callResponse(client, uri, "submitpackage", params).get("error"));
            var sendParams = List.of(hex, "invalid-fee");
            var sendExpected = callResponse(client, reference, "sendrawtransaction", sendParams).get("error");
            assertNotNull(sendExpected);
            assertEquals(sendExpected, callResponse(client, uri, "sendrawtransaction", sendParams).get("error"), "burn precedes fee parsing");
        }
        // The burn cap is per output: two outputs at the cap must reach script
        // validation, even though their sum exceeds the cap. Signatures commit
        // to the original outputs, so this probe cannot consume the fixture coin.
        var splitBurn = new ru.bitcoin.node.protocol.transaction.Transaction(2, List.of(burnInput), List.of(
                new ru.bitcoin.node.protocol.transaction.TxOut(100, new byte[]{0x6a}),
                new ru.bitcoin.node.protocol.transaction.TxOut(100, new byte[]{0x6a})), new ru.bitcoin.node.common.types.UInt32(0));
        var splitBurnParams = List.of(List.of(HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(splitBurn))),
                java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.00000100"));
        var splitBurnExpected = (Map<?, ?>) call(client, reference, "submitpackage", splitBurnParams);
        assertEquals("transaction failed", splitBurnExpected.get("package_msg"));
        assertEquals(splitBurnExpected, call(client, uri, "submitpackage", splitBurnParams));
        var splitSendParams = List.of(rawTransaction(splitBurn), "invalid-fee", new java.math.BigDecimal("0.00000100"));
        var splitSendExpected = callResponse(client, reference, "sendrawtransaction", splitSendParams).get("error");
        assertNotNull(splitSendExpected);
        assertEquals(splitSendExpected, callResponse(client, uri, "sendrawtransaction", splitSendParams).get("error"), "burn cap is per output before fee parsing");
        var submissions = List.of(List.of(invalidPackage), List.of(invalidPackage), List.of(pack),
                List.of(pack, new java.math.BigDecimal("0.00000001")), List.of(cpfp),
                List.of(capped, new java.math.BigDecimal("0.00010000")));
        for (int i = 0; i < submissions.size(); i++) {
            var expected = (Map<?, ?>) call(client, reference, "submitpackage", submissions.get(i));
            assertEquals(i < 2 || i == 5 ? "transaction failed" : "success", expected.get("package_msg"));
            assertEquals(expected, call(client, uri, "submitpackage", submissions.get(i)), "submission case " + i);
            assertEquals(new HashSet<>((List<?>) call(client, reference, "getrawmempool", List.of())),
                    new HashSet<>((List<?>) call(client, uri, "getrawmempool", List.of())));
        }
    }

    private void compareEqualFeeMiningOrder(HttpClient client, URI uri, NodeValidationService validation) throws Exception {
        URI reference = URI.create("http://127.0.0.1:" + rpcPort);
        call(client, reference, "syncwithvalidationinterfacequeue", List.of());
        var coins = ((List<?>) call(client, reference, "listunspent", List.of())).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> new java.math.BigDecimal(value.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) > 0)
                .limit(16).toList();
        var groups = new HashMap<Long, List<String>>();
        for (var coin : coins) {
            String destination = call(client, reference, "getnewaddress", List.of()).toString();
            var amount = new java.math.BigDecimal(coin.get("amount").toString()).subtract(new java.math.BigDecimal("0.00010000"));
            var raw = call(client, reference, "createrawtransaction", List.of(
                    List.of(Map.of("txid", coin.get("txid"), "vout", coin.get("vout"))), Map.of(destination, amount)));
            var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(raw));
            assertEquals(true, signed.get("complete"));
            String hex = signed.get("hex").toString();
            long weight = ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(TransactionParser.parse(HexFormat.of().parseHex(hex)));
            groups.computeIfAbsent(weight, ignored -> new ArrayList<>()).add(hex);
        }
        var selected = groups.values().stream().max(Comparator.comparingInt(List::size)).orElseThrow().stream().limit(8).toList();
        assertTrue(selected.size() >= 4, "Need independent signed transactions with exactly equal fee and weight");
        var ids = new HashSet<String>();
        for (var raw : selected) {
            String id = call(client, reference, "sendrawtransaction", List.of(raw)).toString();
            assertEquals(id, call(client, uri, "sendrawtransaction", List.of(raw)));
            ids.add(id);
        }
        var params = List.of(Map.of("rules", List.of("segwit")));
        var referenceTemplate = (Map<?, ?>) call(client, reference, "getblocktemplate", params);
        var expected = ((List<?>) referenceTemplate.get("transactions")).stream().map(value -> ((Map<?, ?>) value).get("txid").toString())
                .filter(ids::contains).toList();
        assertEquals(selected.size(), expected.size());
        var serializedOrder = ids.stream().map(ru.bitcoin.node.common.types.Hash256::fromDisplayHex)
                .sorted(ru.bitcoin.node.common.types.Hash256::compareRawBytes).map(ru.bitcoin.node.common.types.Hash256::toDisplayHex).toList();
        assertEquals(serializedOrder, expected, "Native Core singleton fallback is serialized txid order");
        assertEquals(expected, validation.mempoolMiningOrder().stream().map(entry -> entry.transaction().txId().toDisplayHex())
                .filter(ids::contains).toList(), "Java relay/mining graph order");
        var localTemplate = (Map<?, ?>) call(client, uri, "getblocktemplate", params);
        assertEquals(expected, ((List<?>) localTemplate.get("transactions")).stream()
                .map(value -> ((Map<?, ?>) value).get("txid").toString()).filter(ids::contains).toList(), "Java block-template order");
    }

    private void compareTrucStates(HttpClient client, URI uri) throws Exception {
        URI reference=URI.create("http://127.0.0.1:"+rpcPort);
        // Earlier submissions notify the wallet asynchronously. The next fixture
        // needs an independent unspent coin, not an accidental equal-fee RBF.
        call(client, reference, "syncwithvalidationinterfacequeue", List.of());
        Map<?, ?> coin = null;
        for (var value : (List<?>) call(client, reference, "listunspent", List.of())) {
            var candidate = (Map<?, ?>) value;
            if (new java.math.BigDecimal(candidate.get("amount").toString()).compareTo(java.math.BigDecimal.ONE) <= 0) continue;
            if (call(client, reference, "gettxout", List.of(candidate.get("txid"), candidate.get("vout"), true)) != null) {
                coin = candidate;
                break;
            }
        }
        assertNotNull(coin, "TRUC fixture needs a coin unspent in Core's current mempool view");
        var fixture=createSubmissionFixture(client,reference,coin,"0.00010000","0.00010000");
        var parent=TransactionParser.parse(HexFormat.of().parseHex(fixture.getFirst()));
        var child=TransactionParser.parse(HexFormat.of().parseHex(fixture.getLast()));
        for(int version:List.of(2,3)) {
            var freeParent=new ru.bitcoin.node.protocol.transaction.Transaction(version,parent.inputs(),List.of(
                    new ru.bitcoin.node.protocol.transaction.TxOut(new java.math.BigDecimal(coin.get("amount").toString()).movePointRight(8).longValueExact(),
                            parent.outputs().getFirst().scriptPubKey())),parent.lockTime());
            var oldInput=child.inputs().getFirst();
            var incompatibleChild=new ru.bitcoin.node.protocol.transaction.Transaction(version==3?2:3,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(
                    new ru.bitcoin.node.protocol.transaction.OutPoint(freeParent.txId(),new ru.bitcoin.node.common.types.UInt32(0)),
                    oldInput.scriptSig(),oldInput.sequence(),oldInput.witness())),child.outputs(),child.lockTime());
            var params=List.of(List.of(rawTransaction(freeParent),rawTransaction(incompatibleChild)));
            var expected=(Map<?,?>)call(client,reference,"submitpackage",params);
            assertTrue(expected.get("package_msg").toString().startsWith("TRUC-violation, "));
            assertEquals(expected,call(client,uri,"submitpackage",params));
        }
        var version3=new ru.bitcoin.node.protocol.transaction.Transaction(3,parent.inputs(),parent.outputs(),parent.lockTime());
        var signed=(Map<?,?>)call(client,reference,"signrawtransactionwithwallet",List.of(rawTransaction(version3)));
        assertEquals(true,signed.get("complete"));String parentHex=signed.get("hex").toString();
        var acceptedParent=TransactionParser.parse(HexFormat.of().parseHex(parentHex));
        assertEquals(call(client,reference,"sendrawtransaction",List.of(parentHex)),call(client,uri,"sendrawtransaction",List.of(parentHex)));
        var old=child.inputs().getFirst();
        var version3Child=new ru.bitcoin.node.protocol.transaction.Transaction(3,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(
                new ru.bitcoin.node.protocol.transaction.OutPoint(acceptedParent.txId(),new ru.bitcoin.node.common.types.UInt32(0)),old.scriptSig(),old.sequence(),old.witness())),child.outputs(),child.lockTime());
        var prevout=Map.of("txid",acceptedParent.txId().toDisplayHex(),"vout",0,"scriptPubKey",HexFormat.of().formatHex(parent.outputs().getFirst().scriptPubKey()),
                "amount",java.math.BigDecimal.valueOf(parent.outputs().getFirst().value(),8));
        var signedChild=(Map<?,?>)call(client,reference,"signrawtransactionwithwallet",List.of(rawTransaction(version3Child),List.of(prevout)));
        assertEquals(true,signedChild.get("complete"));
        String childHex=signedChild.get("hex").toString();var acceptedChild=TransactionParser.parse(HexFormat.of().parseHex(childHex));
        var wrong=new ru.bitcoin.node.protocol.transaction.Transaction(2,acceptedChild.inputs(),acceptedChild.outputs(),acceptedChild.lockTime());String wrongHex=rawTransaction(wrong);
        for(var pack:List.of(List.of(wrongHex),List.of(wrongHex,fixture.getFirst()))) {
            var expected=(List<?>)call(client,reference,"testmempoolaccept",List.of(pack));
            assertEquals("TRUC-violation",((Map<?,?>)expected.getFirst()).get("reject-reason"));
            assertEquals(expected,call(client,uri,"testmempoolaccept",List.of(pack)));
        }
        assertEquals(call(client,reference,"submitpackage",List.of(List.of(wrongHex))),call(client,uri,"submitpackage",List.of(List.of(wrongHex))));
        assertEquals(call(client,reference,"sendrawtransaction",List.of(childHex)),call(client,uri,"sendrawtransaction",List.of(childHex)));
        var expected=(List<?>)call(client,reference,"testmempoolaccept",List.of(List.of(wrongHex)));
        assertEquals("TRUC-violation",((Map<?,?>)expected.getFirst()).get("reject-reason"));
        assertEquals(expected,call(client,uri,"testmempoolaccept",List.of(List.of(wrongHex))),"TRUC before economic RBF failure");
    }

    private static String rawTransaction(ru.bitcoin.node.protocol.transaction.Transaction tx) {
        return HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(tx));
    }

    private static String corruptSubmissionWitness(String raw) {
        var tx = TransactionParser.parse(HexFormat.of().parseHex(raw)); var in = tx.inputs().getFirst();
        var witness = new ArrayList<>(in.witness().items()); witness.getFirst()[10] ^= 1;
        var bad = new ru.bitcoin.node.protocol.transaction.Transaction(tx.version(), List.of(
                new ru.bitcoin.node.protocol.transaction.TxIn(in.previousOutput(), in.scriptSig(), in.sequence(),
                        new ru.bitcoin.node.protocol.transaction.Witness(witness))), tx.outputs(), tx.lockTime());
        return HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(bad));
    }

    private List<String> createSubmissionFixture(HttpClient client, URI reference, Map<?, ?> coin, String parentFee, String childFee) throws Exception {
        String destination = core("getnewaddress").strip();
        var amount = new java.math.BigDecimal(coin.get("amount").toString()).subtract(new java.math.BigDecimal(parentFee));
        var raw = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", coin.get("txid"), "vout", coin.get("vout"))), Map.of(destination, amount)));
        var signed = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(raw));
        assertEquals(true, signed.get("complete"));
        String parentHex = signed.get("hex").toString();
        String parentId = TransactionParser.parse(HexFormat.of().parseHex(parentHex)).txId().toDisplayHex();
        var script = ((Map<?, ?>) call(client, reference, "getaddressinfo", List.of(destination))).get("scriptPubKey");
        var childRaw = call(client, reference, "createrawtransaction", List.of(
                List.of(Map.of("txid", parentId, "vout", 0)), Map.of(core("getnewaddress").strip(), amount.subtract(new java.math.BigDecimal(childFee)))));
        var childSigned = (Map<?, ?>) call(client, reference, "signrawtransactionwithwallet", List.of(childRaw,
                List.of(Map.of("txid", parentId, "vout", 0, "scriptPubKey", script, "amount", amount))));
        assertEquals(true, childSigned.get("complete"));
        return List.of(parentHex, childSigned.get("hex").toString());
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
        var response = callResponse(client, uri, method, params);
        assertNull(response.get("error"), response.toString()); return response.get("result");
    }

    private Map<?, ?> callResponse(HttpClient client, URI uri, String method, Object params) throws Exception {
        var request = HttpRequest.newBuilder(uri).header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id", 1, "method", method, "params", params)))).build();
        return json.readValue(client.send(request, HttpResponse.BodyHandlers.ofString()).body(), Map.class);
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

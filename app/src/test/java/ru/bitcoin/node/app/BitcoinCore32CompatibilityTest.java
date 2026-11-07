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
                        compareOversizedReplacement(client, uri, validation);
                        compareEphemeralDustFees(client, uri, validation);
                        comparePackageProbeFeeModes(client, uri);
                        compareGeneralPackageProbes(client, uri);
                        compareKnownSingleProbes(client, uri);
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
        var missingPackages = List.of(List.of(missingHex), List.of(missingHex, chain.getFirst()),
                List.of(chain.getFirst(), missingHex), List.of(chain.getFirst(), chain.get(1), missingHex),
                List.of(badParentHex, missingChildHex));
        var missingIndices = List.of(0, 0, 1, 2, 1);
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

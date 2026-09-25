package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MiningRpcTest {
    @TempDir
    Path directory;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void authenticatesGatesMiningAndAcceptsBlockAssembledFromRpcTemplate() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory); var peers = new PeerManager()) {
            var validation = new NodeValidationService(db, parameters, () -> 1_800_000_000L, new Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var ready = new AtomicBoolean(false);
            try (var relay = new NodeRelayService(validation, sync, peers)) {
                var mining = new MiningController(validation, relay, parameters, ready::get, new byte[]{0x51}, 4_000_000, new FeeRate(0));
                try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1", 0), "test", "test-password",
                        mining, validation, relay, sync, ready::get); var client = HttpClient.newHttpClient()) {
                    URI uri = URI.create("http://127.0.0.1:" + rpc.port());
                    String body = JSON.writeValueAsString(Map.of("id", 1, "method", "getblocktemplate", "params", List.of(Map.of("rules", List.of("segwit")))));
                    assertEquals(401, client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(-10, ((Number) ((Map<?, ?>) call(client, uri, "getblocktemplate", List.of(Map.of("rules", List.of("segwit")))).get("error")).get("code")).intValue());
                    var chainInfo = (Map<?, ?>) call(client, uri, "getblockchaininfo", List.of()).get("result");
                    assertEquals(false, chainInfo.get("pruned"));
                    assertFalse(chainInfo.containsKey("pruneheight"));
                    ready.set(true);
                    var response = call(client, uri, "getblocktemplate", List.of(Map.of("rules", List.of("segwit"))));
                    assertNull(response.get("error"));
                    var template = (Map<?, ?>) response.get("result");
                    Block mined = mineTemplate(template);
                    var next = client.sendAsync(request(uri, "getblocktemplate", List.of(Map.of("rules", List.of("segwit"),
                            "longpollid", template.get("longpollid")))), HttpResponse.BodyHandlers.ofString());
                    var submitted = call(client, uri, "submitblock", List.of(HexFormat.of().formatHex(BlockSerializer.serialize(mined))));
                    assertNull(submitted.get("error"));
                    assertNull(submitted.get("result"));
                    var updated = (Map<?, ?>) JSON.readValue(next.get(5, TimeUnit.SECONDS).body(), Map.class).get("result");
                    assertEquals(mined.hash().toDisplayHex(), updated.get("previousblockhash"));
                    assertEquals(false, updated.get("submitold"));
                    assertEquals(mined.hash(), validation.activeTip().hash());
                    assertEquals(mined.hash(), sync.headerChainState().bestHeaderTip().hash());
                    assertEquals("duplicate", call(client, uri, "submitblock", List.of(HexFormat.of().formatHex(BlockSerializer.serialize(mined)))).get("result"));
                    assertNotNull(call(client, uri, "submitblock", List.of("zz")).get("error"));
                    assertEquals(-32601, ((Number) ((Map<?, ?>) call(client, uri, "unknown", List.of()).get("error")).get("code")).intValue());
                }
            }
        }
    }

    static Block mineTemplate(Map<?, ?> template) {
        var parameters = NetworkParametersRegistry.regtest();
        var selected = new ArrayList<Transaction>();
        for (var value : (List<?>) template.get("transactions")) {
            selected.add(TransactionParser.parse(HexFormat.of().parseHex((String) ((Map<?, ?>) value).get("data"))));
        }
        long height = ((Number) template.get("height")).longValue();
        long reward = ((Number) template.get("coinbasevalue")).longValue();
        long fees = reward - ru.bitcoin.node.consensus.money.BlockSubsidy.calculate(height, parameters);
        var coinbase = CoinbaseBuilder.build(height, parameters, fees, new byte[]{0x51}, new byte[8], selected);
        var transactions = new ArrayList<Transaction>();
        transactions.add(coinbase);
        transactions.addAll(selected);
        var candidate = new Block(new BlockHeader(((Number) template.get("version")).intValue(),
                ru.bitcoin.node.common.types.Hash256.fromDisplayHex((String) template.get("previousblockhash")),
                MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList()),
                new UInt32(((Number) template.get("curtime")).longValue()),
                new UInt32(Long.parseUnsignedLong((String) template.get("bits"), 16)), new UInt32(0)), transactions);
        return NonceMiner.search(candidate, parameters, 0, 100_000, () -> false).orElseThrow();
    }

    private static HttpRequest request(URI uri, String method, List<?> params) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("id", 1, "method", method, "params", params)))).build();
    }

    private static Map<?, ?> call(HttpClient client, URI uri, String method, List<?> params) throws Exception {
        return JSON.readValue(client.send(request(uri, method, params), HttpResponse.BodyHandlers.ofString()).body(), Map.class);
    }
}

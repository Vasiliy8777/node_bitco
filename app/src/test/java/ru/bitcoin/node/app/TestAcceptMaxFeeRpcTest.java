package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestAcceptMaxFeeRpcTest {
    @TempDir Path directory;
    @ParameterizedTest
    @CsvSource({"1000,1000000,20000,true", "10000,-9000,20000,false", "1,0,1,true",
            "2,0,1,false", "10000,0,0,true", "2000,0,20000,true"})
    void capUsesBaseFeeAndRoundedSatoshiAmount(long base, long delta, long rate, boolean allowed) throws Exception {
        var tx = transaction(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0)));
        var result = call(List.of(new MempoolEntry(tx, base, 400, 0, 0, delta)), rate);
        assertEquals(allowed, ((Map<?, ?>) result.getFirst()).get("allowed"));
        if (!allowed) assertEquals("max-fee-exceeded", ((Map<?, ?>) result.getFirst()).get("reject-reason"));
    }
    @Test void capFailurePreservesEarlierSuccessAndSkipsFollowingEntries() throws Exception {
        var first = transaction(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0)));
        var second = transaction(new OutPoint(first.txId(), new UInt32(0)));
        var third = transaction(new OutPoint(second.txId(), new UInt32(0)));
        var result = call(List.of(new MempoolEntry(first, 1000, 400, 0),
                new MempoolEntry(second, 10000, 400, 0), new MempoolEntry(third, 1000, 400, 0)), 20000);
        assertEquals(true, ((Map<?, ?>) result.get(0)).get("allowed"));
        assertEquals(Map.of("txid", second.txId().toDisplayHex(), "wtxid", second.wtxId().toDisplayHex(),
                "allowed", false, "reject-reason", "max-fee-exceeded"), result.get(1));
        assertEquals(Map.of("txid", third.txId().toDisplayHex(), "wtxid", third.wtxId().toDisplayHex()), result.get(2));
    }
    private List<?> call(List<MempoolEntry> entries, long rate) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var db = new RocksDbDatabase(directory)) {
            var validation = org.mockito.Mockito.spy(new NodeValidationService(db, parameters, () -> 1800000000L, new Mempool()));
            org.mockito.Mockito.doReturn(new Mempool.TestAcceptResult(entries, null)).when(validation)
                    .testMempoolAccept(org.mockito.ArgumentMatchers.anyList());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1800000000L);
            var mining = new MiningController(validation, null, parameters, () -> true, new byte[]{0x51}, 4000000, new FeeRate(0));
            try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1", 0), "test", "test-password",
                    mining, validation, null, sync, () -> true); var client = HttpClient.newHttpClient()) {
                var json = JsonMapper.builder().build();
                var raw = entries.stream().map(e -> HexFormat.of().formatHex(TransactionSerializer.serialize(e.transaction()))).toList();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + rpc.port()))
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id", 1, "method", "testmempoolaccept",
                                "params", List.of(raw, java.math.BigDecimal.valueOf(rate, 8)))))).build();
                var response = json.readValue(client.send(request, HttpResponse.BodyHandlers.ofString()).body(), Map.class);
                assertNull(response.get("error"), response.toString());
                return (List<?>) response.get("result");
            }
        }
    }
    private static Transaction transaction(OutPoint point) {
        return new Transaction(2, List.of(new TxIn(point, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
    }
}

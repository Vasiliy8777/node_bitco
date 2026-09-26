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
                        mining, validation, relay, sync, ready::get, peers); var client = HttpClient.newHttpClient()) {
                    URI uri = URI.create("http://127.0.0.1:" + rpc.port());
                    String body = JSON.writeValueAsString(Map.of("id", 1, "method", "getblocktemplate", "params", List.of(Map.of("rules", List.of("segwit")))));
                    assertEquals(401, client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(-10, ((Number) ((Map<?, ?>) call(client, uri, "getblocktemplate", List.of(Map.of("rules", List.of("segwit")))).get("error")).get("code")).intValue());
                    var chainInfo = (Map<?, ?>) call(client, uri, "getblockchaininfo", List.of()).get("result");
                    assertEquals(false, chainInfo.get("pruned"));
                    assertFalse(chainInfo.containsKey("pruneheight"));
                    var pruneDisabled = (Map<?, ?>) call(client, uri, "pruneblockchain", List.of(0)).get("error");
                    assertEquals(-1, ((Number) pruneDisabled.get("code")).intValue());
                    assertEquals(true, chainInfo.get("initialblockdownload"));
                    assertEquals(0, ((Number) call(client, uri, "getconnectioncount", List.of()).get("result")).intValue());
                    assertEquals(List.of(), call(client, uri, "getpeerinfo", List.of()).get("result"));
                    assertNull(call(client, uri, "setban", List.of("192.0.2.99/24", "add", 3600, false)).get("error"));
                    var banned = (List<?>) call(client, uri, "listbanned", List.of()).get("result");
                    assertEquals(1, banned.size());
                    assertEquals("192.0.2.0/24", ((Map<?, ?>) banned.getFirst()).get("address"));
                    assertNull(call(client, uri, "clearbanned", List.of()).get("error"));
                    assertEquals(List.of(), call(client, uri, "listbanned", List.of()).get("result"));
                    ready.set(true);
                    var response = call(client, uri, "getblocktemplate", List.of(Map.of("rules", List.of("segwit"))));
                    assertNull(response.get("error"));
                    var template = (Map<?, ?>) response.get("result");
                    assertEquals(List.of("proposal"), template.get("capabilities"));
                    Block mined = mineTemplate(template);

                    // BIP23 proposal validates the complete candidate without requiring/recording PoW.
                    Block proposalBlock = mined;
                    for (long nonce = 0; nonce <= 0xffff_ffffL; nonce++) {
                        var header = new BlockHeader(
                                mined.header().version(), mined.header().previousBlockHash(), mined.header().merkleRoot(),
                                mined.header().timestamp(), mined.header().bits(), new UInt32(nonce));
                        if (!ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(header, parameters)) {
                            proposalBlock = new Block(header, mined.transactions());
                            break;
                        }
                    }
                    assertFalse(ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(proposalBlock.header(), parameters));
                    var proposal = call(client, uri, "getblocktemplate", List.of(Map.of(
                            "mode", "proposal",
                            "data", HexFormat.of().formatHex(BlockSerializer.serialize(proposalBlock)))));
                    assertNull(proposal.get("error"));
                    assertNull(proposal.get("result"));
                    assertEquals(0, validation.activeTip().height());

                    var badHeader = new BlockHeader(
                            mined.header().version(), mined.header().previousBlockHash(),
                            new ru.bitcoin.node.common.types.Hash256(new byte[32]),
                            mined.header().timestamp(), mined.header().bits(), mined.header().nonce());
                    var badProposalBlock = new Block(badHeader, mined.transactions());
                    var badProposal = call(client, uri, "getblocktemplate", List.of(Map.of(
                            "mode", "proposal",
                            "data", HexFormat.of().formatHex(BlockSerializer.serialize(badProposalBlock)))));
                    assertNull(badProposal.get("error"));
                    assertNotNull(badProposal.get("result"));
                    assertEquals(0, validation.activeTip().height());
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
                    assertFalse(validation.isInitialBlockDownload());

                    // Manual chain control uses the same persistent failure/reorg machinery.
                    assertNull(call(client, uri, "invalidateblock", List.of(mined.hash().toDisplayHex())).get("error"));
                    assertEquals(0, validation.activeTip().height());
                    assertNull(call(client, uri, "reconsiderblock", List.of(mined.hash().toDisplayHex())).get("error"));
                    assertEquals(mined.hash(), validation.activeTip().hash());

                    var chainTips = (List<?>) call(client, uri, "getchaintips", List.of()).get("result");
                    assertEquals(1, chainTips.size());
                    var onlyTip = (Map<?, ?>) chainTips.getFirst();
                    assertEquals(mined.hash().toDisplayHex(), onlyTip.get("hash"));
                    assertEquals(0, ((Number) onlyTip.get("branchlen")).intValue());
                    assertEquals("active", onlyTip.get("status"));

                    // Core-compatible active-chain read RPCs.
                    assertEquals(
                            mined.hash().toDisplayHex(),
                            call(client, uri, "getblockhash", List.of(1)).get("result")
                    );
                    var headerJson = (Map<?, ?>) call(
                            client, uri, "getblockheader", List.of(mined.hash().toDisplayHex())
                    ).get("result");
                    assertEquals(mined.hash().toDisplayHex(), headerJson.get("hash"));
                    assertEquals(1, ((Number) headerJson.get("confirmations")).intValue());
                    assertEquals(1, ((Number) headerJson.get("height")).intValue());
                    assertEquals(
                            HexFormat.of().formatHex(BlockHeaderSerializer.serialize(mined.header())),
                            call(client, uri, "getblockheader",
                                    List.of(mined.hash().toDisplayHex(), false)).get("result")
                    );

                    var blockJson = (Map<?, ?>) call(
                            client, uri, "getblock", List.of(mined.hash().toDisplayHex())
                    ).get("result");
                    assertEquals(mined.hash().toDisplayHex(), blockJson.get("hash"));
                    assertEquals(mined.transactions().size(), ((Number) blockJson.get("nTx")).intValue());
                    assertEquals(
                            mined.transactions().stream().map(tx -> tx.txId().toDisplayHex()).toList(),
                            blockJson.get("tx")
                    );
                    assertEquals(
                            HexFormat.of().formatHex(BlockSerializer.serialize(mined)),
                            call(client, uri, "getblock",
                                    List.of(mined.hash().toDisplayHex(), 0)).get("result")
                    );

                    var coinbase = mined.transactions().getFirst();
                    var txout = (Map<?, ?>) call(client, uri, "gettxout",
                            List.of(coinbase.txId().toDisplayHex(), 0)).get("result");
                    assertNotNull(txout);
                    assertEquals(1, ((Number) txout.get("confirmations")).intValue());
                    assertEquals(true, txout.get("coinbase"));
                    var utxoInfo = (Map<?, ?>) call(client, uri, "gettxoutsetinfo", List.of()).get("result");
                    assertEquals(1, ((Number) utxoInfo.get("height")).intValue());
                    assertEquals(mined.hash().toDisplayHex(), utxoInfo.get("bestblock"));
                    assertTrue(((Number) utxoInfo.get("txouts")).longValue() >= 1L);
                    assertTrue(((Number) utxoInfo.get("total_amount")).doubleValue() > 0.0);
                    assertEquals(
                            HexFormat.of().formatHex(TransactionSerializer.serialize(coinbase)),
                            call(client, uri, "getrawtransaction",
                                    List.of(coinbase.txId().toDisplayHex(), 0,
                                            mined.hash().toDisplayHex())).get("result")
                    );
                    var txJson = (Map<?, ?>) call(
                            client, uri, "getrawtransaction",
                            List.of(coinbase.txId().toDisplayHex(), 1,
                                    mined.hash().toDisplayHex())
                    ).get("result");
                    assertEquals(coinbase.txId().toDisplayHex(), txJson.get("txid"));
                    assertEquals(coinbase.wtxId().toDisplayHex(), txJson.get("hash"));
                    assertEquals(mined.hash().toDisplayHex(), txJson.get("blockhash"));
                    assertEquals(1, ((Number) txJson.get("confirmations")).intValue());

                    var decoded = (Map<?, ?>) call(
                            client, uri, "decoderawtransaction",
                            List.of(HexFormat.of().formatHex(TransactionSerializer.serialize(coinbase)))
                    ).get("result");
                    assertEquals(coinbase.txId().toDisplayHex(), decoded.get("txid"));
                    assertEquals(coinbase.outputs().size(), ((List<?>) decoded.get("vout")).size());

                    assertEquals(
                            -5,
                            ((Number) ((Map<?, ?>) call(client, uri, "getrawtransaction",
                                    List.of(coinbase.txId().toDisplayHex())).get("error")).get("code")).intValue()
                    );

                    assertEquals(
                            -8,
                            ((Number) ((Map<?, ?>) call(client, uri, "getblockhash",
                                    List.of(2)).get("error")).get("code")).intValue()
                    );
                    assertEquals(
                            -5,
                            ((Number) ((Map<?, ?>) call(client, uri, "getblockheader",
                                    List.of("00".repeat(32))).get("error")).get("code")).intValue()
                    );
                    assertEquals("duplicate", call(client, uri, "submitblock", List.of(HexFormat.of().formatHex(BlockSerializer.serialize(mined)))).get("result"));
                    assertEquals("duplicate", call(client, uri, "getblocktemplate", List.of(Map.of(
                            "mode", "proposal",
                            "data", HexFormat.of().formatHex(BlockSerializer.serialize(mined))))).get("result"));
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

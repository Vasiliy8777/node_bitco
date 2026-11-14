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
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Compare the HTTP envelope as well as complete native JSON-RPC replies. */
class BitcoinCoreRpcTransportTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private int blockReadComparisons;
    private record Reply(int status,String contentType,String challenge,Object body) { }
    private record Request(String verb,String path,String auth,String body) { }
    private static final String AUTH="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(StandardCharsets.UTF_8));

    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void transportParserBatchAndNotificationsMatchCore() throws Exception {
        Path data=Files.createDirectory(directory.resolve("core"));int port;
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var process=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,
                "-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-acceptnonstdtxn=1","-prune=1","-fastprune=1",
                "-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI core=URI.create("http://127.0.0.1:"+port);
        try(var client=HttpClient.newHttpClient()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true) {
                try {
                    var reply=send(client,core,new Request("POST","/",AUTH,"{\"id\":1,\"method\":\"getblockcount\"}"));
                    if(reply.body instanceof Map<?,?> map && map.get("error")==null) break;
                } catch(Exception error){if(!process.isAlive())throw error;}
                if(System.nanoTime()>deadline) throw new IllegalStateException("Core did not initialize");
                Thread.sleep(50);
            }
            var parameters=NetworkParametersRegistry.regtest();
            try(var db=new RocksDbDatabase(directory.resolve("java"));var peers=new PeerManager();
                var validation=new NodeValidationService(db,parameters,()->System.currentTimeMillis()/1000,new Mempool(),1)) {
                var sync=new NodeSyncInfrastructure(db,parameters,()->System.currentTimeMillis()/1000);
                try(var relay=new NodeRelayService(validation,sync,peers)) {
                    var mining=new MiningController(validation,relay,parameters,()->true,new byte[]{0x51},4000000,new FeeRate(1));
                    try(var rpc=new NodeRpcServer(new InetSocketAddress("127.0.0.1",0),"test","test-password",mining,validation,relay,sync,()->true,peers)) {
                        URI local=URI.create("http://127.0.0.1:"+rpc.port());
                        var failures=new ArrayList<String>();int count=0;
                        for(var request:requests()) {
                            Reply expected=send(client,core,request),actual=send(client,local,request);count++;
                            if(!expected.equals(actual)) failures.add(request+"\nEXPECTED "+expected+"\nACTUAL "+actual);
                        }
                        for (boolean hidden : List.of(false, true)) {
                            String body = json.writeValueAsString(Map.of("id", 1, "method", "getopenrpcinfo", "params", List.of(hidden)));
                            var reference = (Map<?, ?>) ((Map<?, ?>) send(client, core, new Request("POST", "/", AUTH, body)).body).get("result");
                            var actual = (Map<?, ?>) ((Map<?, ?>) send(client, local, new Request("POST", "/", AUTH, body)).body).get("result");
                            var expectedMethods = new HashMap<String, Object>();
                            for (Object item : (List<?>) reference.get("methods")) expectedMethods.put(((Map<?, ?>) item).get("name").toString(), item);
                            for (Object item : (List<?>) actual.get("methods")) {
                                String name = ((Map<?, ?>) item).get("name").toString();
                                assertEquals(expectedMethods.get(name), item, "Full OpenRPC descriptor " + name + " hidden=" + hidden);
                                count++;
                                if (hidden) {
                                    String help = json.writeValueAsString(Map.of("id", 1, "method", "help", "params", List.of(name)));
                                    assertEquals(send(client, core, new Request("POST", "/", AUTH, help)), send(client, local, new Request("POST", "/", AUTH, help)), "Full help " + name);
                                    count++;
                                    var definition = (Map<?, ?>) item;
                                    var argumentDefinitions = (List<?>) definition.get("params");
                                    var badParams = new ArrayList<Object>(Collections.nCopies(argumentDefinitions.size() + 1, null));
                                    var invalid = new ArrayList<Object>();
                                    invalid.add(badParams);
                                    invalid.add(Map.of("unrecognized_test_parameter", 1));
                                    if (argumentDefinitions.stream().anyMatch(p -> Boolean.TRUE.equals(((Map<?, ?>) p).get("required")))) invalid.add(List.of());
                                    for (int i = 0; i < argumentDefinitions.size(); i++) {
                                        var wrong = new ArrayList<Object>(Collections.nCopies(argumentDefinitions.size(), null));
                                        var schema = (Map<?, ?>) ((Map<?, ?>) argumentDefinitions.get(i)).get("schema");
                                        wrong.set(i, "boolean".equals(schema.get("type")) ? "bad-type" : true);
                                        invalid.add(wrong);
                                    }
                                    for (Object params : invalid) {
                                        String raw = json.writeValueAsString(Map.of("id", 1, "method", name, "params", params));
                                        Reply expectedReply = send(client, core, new Request("POST", "/", AUTH, raw));
                                        Reply actualReply = send(client, local, new Request("POST", "/", AUTH, raw));
                                        count++;
                                        if (!expectedReply.equals(actualReply)) failures.add(name + " params=" + params + "\nEXPECTED " + expectedReply + "\nACTUAL " + actualReply);
                                    }
                                }
                            }
                        }
                        System.out.println("CORE32_RPC_TRANSPORT comparisons="+count+" discrepancies="+failures.size());
                        for(var failure:failures)System.out.println("RPC_TRANSPORT_DIFFERENCE "+failure);
                        assertTrue(failures.isEmpty(),String.join("\n",failures.stream().limit(12).toList()));
                        compareProofs(client, core, local, validation, db);
                        compareHeaders(client, core, local, validation);
                        System.out.println("CORE32_BLOCK_READ comparisons="+blockReadComparisons+" discrepancies=0");
                    }
                }
            }
        } finally {
            try(var client=HttpClient.newHttpClient()){send(client,core,new Request("POST","/",AUTH,"{\"id\":1,\"method\":\"stop\"}"));}
            catch(Exception ignored){}
            if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private Object call(HttpClient client, URI endpoint, String method, Object params) throws Exception {
        String raw = json.writeValueAsString(Map.of("id", 1, "method", method, "params", params));
        var reply = (Map<?, ?>) send(client, endpoint, new Request("POST", "/", AUTH, raw)).body;
        assertNull(reply.get("error"), method + " " + reply);
        return reply.get("result");
    }
    private void compareProofs(HttpClient client, URI core, URI local, NodeValidationService validation, RocksDbDatabase database) throws Exception {
        var initial = (List<?>) call(client, core, "generatetodescriptor", List.of(110, "raw(51)"));
        var funding = new ArrayList<String>();
        for (int i = 0; i < initial.size(); i++) {
            String hash = initial.get(i).toString();
            var block = ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(hash, 0)).toString()));
            validation.processBlock(block);
            if (i < 9) funding.add(block.transactions().getFirst().txId().toDisplayHex());
        }
        for (int i = 0; i < 2; i++) compareBlockReads(client, core, local, initial.get(i).toString(), i + 1);
        compareBlockReads(client, core, local, call(client, core, "getblockhash", List.of(0)).toString(), 0);
        addWitnessBlock(client, core, local, validation, initial.get(9).toString());
        int spent = 0, comparisons = 0;
        var proofs = new ArrayList<String>();
        for (int batch : List.of(2, 3, 4)) {
            for (int i = 0; i < batch; i++) {
                Object raw = call(client, core, "createrawtransaction", List.of(List.of(Map.of("txid", funding.get(spent++), "vout", 0)), Map.of("data", "01".repeat(32))));
                call(client, core, "sendrawtransaction", List.of(raw, 0));
            }
            String hash = ((List<?>) call(client, core, "generatetodescriptor", List.of(1, "raw(51)"))).getFirst().toString();
            var block = ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(hash, 0)).toString()));
            validation.processBlock(block);
            assertEquals(batch + 1, block.transactions().size());
            compareBlockReads(client, core, local, hash, validation.activeTip().height());
            for (int mask = 1; mask < (1 << block.transactions().size()); mask++) {
                var txids = new ArrayList<String>();
                for (int i = 0; i < block.transactions().size(); i++) if ((mask & (1 << i)) != 0) txids.add(block.transactions().get(i).txId().toDisplayHex());
                String nativeProof = call(client, core, "gettxoutproof", List.of(txids, hash)).toString();
                assertEquals(nativeProof, call(client, local, "gettxoutproof", List.of(txids, hash)), "Exact partial Merkle serialization"); comparisons++;
                assertEquals(call(client, core, "verifytxoutproof", List.of(nativeProof)), call(client, local, "verifytxoutproof", List.of(nativeProof))); comparisons++;
                String raw = json.writeValueAsString(Map.of("id", 1, "method", "gettxoutproof", "params", List.of(txids)));
                assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw)), "UTXO lookup without txindex"); comparisons++;
                proofs.add(nativeProof);
            }
        }
        for (String proof : proofs) {
            for (int position : List.of(0, 36, 80, 84, proof.length() / 2 - 1)) {
                byte[] bytes = HexFormat.of().parseHex(proof); bytes[position] ^= 1;
                String raw = json.writeValueAsString(Map.of("id", 1, "method", "verifytxoutproof", "params", List.of(HexFormat.of().formatHex(bytes))));
                assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw)), "Corrupted proof at " + position); comparisons++;
            }
            for (String suffix : List.of("", "00", "ffff")) {
                String raw = json.writeValueAsString(Map.of("id", 1, "method", "verifytxoutproof", "params", List.of(proof + suffix)));
                assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw))); comparisons++;
            }
        }
        for (String proof : List.of("", "x", "0", "00", "00".repeat(80), "00".repeat(84), proofs.getFirst().substring(0, 170))) {
            String raw = json.writeValueAsString(Map.of("id", 1, "method", "verifytxoutproof", "params", List.of(proof)));
            assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw)), "Malformed proof " + proof); comparisons++;
        }
        String finalHash = validation.activeTip().hash().toDisplayHex();
        call(client, core, "invalidateblock", List.of(finalHash)); validation.invalidateBlock(ru.bitcoin.node.common.types.Hash256.fromDisplayHex(finalHash));
        compareBlockReads(client, core, local, finalHash, -1);
        String sideProof = proofs.getLast();
        String request = json.writeValueAsString(Map.of("id", 1, "method", "verifytxoutproof", "params", List.of(sideProof)));
        assertEquals(send(client, core, new Request("POST", "/", AUTH, request)), send(client, local, new Request("POST", "/", AUTH, request))); comparisons++;
        var sideIds = ru.bitcoin.node.protocol.block.MerkleBlockProof.extract(HexFormat.of().parseHex(sideProof)).matches().stream().map(ru.bitcoin.node.common.types.Hash256::toDisplayHex).toList();
        assertEquals(call(client, core, "gettxoutproof", List.of(sideIds, finalHash)), call(client, local, "gettxoutproof", List.of(sideIds, finalHash))); comparisons++;
        call(client, core, "reconsiderblock", List.of(finalHash)); validation.reconsiderBlock(ru.bitcoin.node.common.types.Hash256.fromDisplayHex(finalHash));
        var more = (List<?>) call(client, core, "generatetodescriptor", List.of(1000, "raw(51)"));
        for (Object hash : more) validation.processBlock(ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(hash, 0)).toString())));
        call(client, core, "pruneblockchain", List.of(800));
        var recovered = new ru.bitcoin.node.chain.ChainInitializer(database, validation.networkParameters()).initialize();
        assertEquals(validation.activeTip().hash(), recovered.activeTip().hash(), "Startup after pruning genesis");
        assertTrue(new ru.bitcoin.node.storage.block.RocksDbBlockStore(database).find(validation.networkParameters().genesisBlockHash()).isEmpty(), "Startup must preserve pruned genesis availability");
        for (String proof : proofs) {
            request = json.writeValueAsString(Map.of("id", 1, "method", "verifytxoutproof", "params", List.of(proof)));
            assertEquals(send(client, core, new Request("POST", "/", AUTH, request)), send(client, local, new Request("POST", "/", AUTH, request)), "Verify after pruning"); comparisons++;
        }
        request = json.writeValueAsString(Map.of("id", 1, "method", "gettxoutproof", "params", List.of(sideIds, finalHash)));
        assertEquals(send(client, core, new Request("POST", "/", AUTH, request)), send(client, local, new Request("POST", "/", AUTH, request)), "Create proof from pruned data"); comparisons++;
        System.out.println("CORE32_TXOUT_PROOF comparisons=" + comparisons + " discrepancies=0");
    }
    private void compareHeaders(HttpClient client, URI core, URI local, NodeValidationService validation) throws Exception {
        var parent = validation.activeTip();
        long now = System.currentTimeMillis() / 1000;
        long timestamp = Math.max(now, parent.header().timestamp().value() + 1);
        var root = ru.bitcoin.node.common.types.Hash256.fromDisplayHex("42".repeat(32));
        var valid = minedHeader(0x20000000, parent.hash(), root, timestamp, parent.header().bits().value(), true);
        var invalidPow = minedHeader(0x20000000, parent.hash(), root, timestamp, parent.header().bits().value(), false);
        var cases = new ArrayList<String>(List.of("", "00", "0", "x", "00".repeat(79)));
        cases.add(headerHex(ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(validation.networkParameters()).header()));
        cases.add(headerHex(minedHeader(0x20000000, root, root, timestamp, parent.header().bits().value(), false)));
        cases.add(headerHex(invalidPow));
        cases.add(headerHex(minedHeader(0x20000000, parent.hash(), root, timestamp, 0x207ffffeL, true)));
        cases.add(headerHex(minedHeader(0x20000000, parent.hash(), root, 0, parent.header().bits().value(), true)));
        cases.add(headerHex(minedHeader(0x20000000, parent.hash(), root, now + 14400, parent.header().bits().value(), true)));
        for (int version : List.of(-1, 0, 1, 2, 3)) cases.add(headerHex(minedHeader(version, parent.hash(), root, timestamp, parent.header().bits().value(), true)));
        for (int version : List.of(0, 3)) {
            cases.add(headerHex(minedHeader(version, parent.hash(), root, 0, parent.header().bits().value(), true)));
            cases.add(headerHex(minedHeader(version, parent.hash(), root, now + 14400, parent.header().bits().value(), true)));
            cases.add(headerHex(minedHeader(version, parent.hash(), root, 0, 0x207ffffeL, true)));
        }
        cases.add(headerHex(valid)); cases.add(headerHex(valid)); cases.add(headerHex(valid) + "00ffff");
        int comparisons = 0;
        for (String hex : cases) { compareHeader(client, core, local, hex); comparisons++; }
        assertEquals(call(client, core, "getblockheader", List.of(valid.hash().toDisplayHex())), call(client, local, "getblockheader", List.of(valid.hash().toDisplayHex())), "Header-only block JSON"); blockReadComparisons++;
        compareReadErrors(client, core, local, valid.hash().toDisplayHex(), parent.hash().toDisplayHex());
        assertEquals(parent.height(), ((Number) call(client, local, "getblockcount", List.of())).longValue(), "Headers alone must not activate a block");
        call(client, core, "invalidateblock", List.of(parent.hash().toDisplayHex())); validation.invalidateBlock(parent.hash());
        compareHeader(client, core, local, headerHex(valid)); comparisons++;
        compareHeader(client, core, local, headerHex(invalidPow)); comparisons++;
        compareHeader(client, core, local, headerHex(minedHeader(0x20000000, parent.hash(), root, timestamp + 1, parent.header().bits().value(), true))); comparisons++;
        compareHeader(client, core, local, headerHex(minedHeader(0x20000000, valid.hash(), root, timestamp + 2, parent.header().bits().value(), true))); comparisons++;
        call(client, core, "reconsiderblock", List.of(parent.hash().toDisplayHex())); validation.reconsiderBlock(parent.hash());
        compareHeader(client, core, local, headerHex(valid)); comparisons++;
        System.out.println("CORE32_SUBMIT_HEADER comparisons=" + comparisons + " discrepancies=0");
    }
    private void compareBlockReads(HttpClient client, URI core, URI local, String hash, long height) throws Exception {
        for (Object target : height < 0 ? List.of(hash) : List.of(hash, height)) {
            var expected = (Map<?, ?>) call(client, core, "getblockstats", List.of(target));
            assertEquals(expected, call(client, local, "getblockstats", List.of(target)), "All block statistics " + target);
            blockReadComparisons++;
            for (Object key : expected.keySet()) {
                assertEquals(call(client, core, "getblockstats", List.of(target, List.of(key))), call(client, local, "getblockstats", List.of(target, List.of(key))), "Selected statistic " + key);
                blockReadComparisons++;
            }
        }
        assertEquals(call(client, core, "getblockheader", List.of(hash)), call(client, local, "getblockheader", List.of(hash)), "Full header JSON");
        blockReadComparisons++;
        for (Object verbosity : List.of(-1, 0, false, 1, true, 2, 3, 4)) {
            assertEquals(call(client, core, "getblock", List.of(hash, verbosity)), call(client, local, "getblock", List.of(hash, verbosity)), "Full block JSON verbosity=" + verbosity);
            blockReadComparisons++;
        }
        var block = ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(hash, 0)).toString()));
        for (var tx : block.transactions()) for (Object verbosity : List.of(-1, 0, false, 1, true, 2, 3)) {
            String raw = json.writeValueAsString(Map.of("id", 1, "method", "getrawtransaction", "params", List.of(tx.txId().toDisplayHex(), verbosity, hash)));
            assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw)), "Full raw transaction verbosity="+verbosity);
            blockReadComparisons++;
        }
    }
    private void compareReadErrors(HttpClient client, URI core, URI local, String headerOnly, String pruned) throws Exception {
        var tests = new ArrayList<Map<String,Object>>();
        String archived = call(client,core,"getblockhash",List.of(1)).toString();
        for (Object target : Arrays.asList(-1,2147483648L,0.5,1.0,true,null,List.of(),Map.of(),"x","11".repeat(32),headerOnly,pruned,archived)) {
            tests.add(Map.of("method","getblockstats","params",Arrays.asList(target)));
            for(Object selected : Arrays.asList(List.of("unknown"),List.of(true),Collections.singletonList(null),List.of("height","time"),List.of(),List.of("\uE000","\uD83D\uDCA9")))
                tests.add(Map.of("method","getblockstats","params",Arrays.asList(target,selected)));
        }
        for (String method : List.of("getblock","getblockheader")) for(String hash : List.of("x","z".repeat(64),"11".repeat(32),headerOnly,pruned,archived)) {
            tests.add(Map.of("method",method,"params",List.of(hash)));
            for(Object verbosity : Arrays.asList(null,0.5,2147483648L,"bad",List.of(),Map.of())) tests.add(Map.of("method",method,"params",Arrays.asList(hash,verbosity)));
        }
        String genesis = call(client,core,"getblockhash",List.of(0)).toString();
        String genesisTx = ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).transactions().getFirst().txId().toDisplayHex();
        for(String txid : List.of("x","11".repeat(32),genesisTx)) for(Object verbosity : Arrays.asList(null,-1,0.5,"bad",2))
            for(Object hash : Arrays.asList(null,"x",headerOnly,pruned,genesis,archived)) tests.add(Map.of("method","getrawtransaction","params",Arrays.asList(txid,verbosity,hash)));
        for(var test:tests) {
            var request = new LinkedHashMap<String,Object>(test); request.put("id",1);
            String raw = json.writeValueAsString(request);
            assertEquals(send(client,core,new Request("POST","/",AUTH,raw)), send(client,local,new Request("POST","/",AUTH,raw)), "Read error "+test);
            blockReadComparisons++;
        }
    }
    private void addWitnessBlock(HttpClient client, URI core, URI local, NodeValidationService validation, String fundingBlock) throws Exception {
        var block = ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(fundingBlock, 0)).toString()));
        String address = ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.witness(0, ru.bitcoin.node.crypto.hash.Sha256.hash(new byte[]{0x51}), validation.networkParameters());
        String parent = call(client, core, "createrawtransaction", List.of(List.of(Map.of("txid", block.transactions().getFirst().txId().toDisplayHex(), "vout", 0)), Map.of(address,49))).toString();
        String txid = call(client, core, "sendrawtransaction", List.of(parent, 0)).toString();
        var child = new ru.bitcoin.node.protocol.transaction.Transaction(2,
                List.of(new ru.bitcoin.node.protocol.transaction.TxIn(new ru.bitcoin.node.protocol.transaction.OutPoint(ru.bitcoin.node.common.types.Hash256.fromDisplayHex(txid), new ru.bitcoin.node.common.types.UInt32(0)), new byte[0],
                        new ru.bitcoin.node.common.types.UInt32(0xfffffffdL), new ru.bitcoin.node.protocol.transaction.Witness(List.of(new byte[]{0x51})))),
                List.of(new ru.bitcoin.node.protocol.transaction.TxOut(4_800_000_000L,ru.bitcoin.node.protocol.address.SegwitAddressDecoder.toScriptPubKey(address,validation.networkParameters()))),new ru.bitcoin.node.common.types.UInt32(0));
        call(client, core, "sendrawtransaction", List.of(HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(child)),0));
        String hash = ((List<?>) call(client, core, "generatetodescriptor", List.of(1,"raw(51)"))).getFirst().toString();
        validation.processBlock(ru.bitcoin.node.protocol.serialization.BlockParser.parse(HexFormat.of().parseHex(call(client, core, "getblock", List.of(hash,0)).toString())));
        compareBlockReads(client,core,local,hash,validation.activeTip().height());
    }
    private void compareHeader(HttpClient client, URI core, URI local, String hex) throws Exception {
        String raw = json.writeValueAsString(Map.of("id", 1, "method", "submitheader", "params", List.of(hex)));
        assertEquals(send(client, core, new Request("POST", "/", AUTH, raw)), send(client, local, new Request("POST", "/", AUTH, raw)), "submitheader " + hex);
    }
    private String headerHex(ru.bitcoin.node.protocol.block.BlockHeader header) {
        return HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer.serialize(header));
    }
    private ru.bitcoin.node.protocol.block.BlockHeader minedHeader(int version, ru.bitcoin.node.common.types.Hash256 parent,
            ru.bitcoin.node.common.types.Hash256 root, long time, long bits, boolean valid) {
        for (long nonce = 0; nonce < 10000; nonce++) {
            var header = new ru.bitcoin.node.protocol.block.BlockHeader(version, parent, root,
                    new ru.bitcoin.node.common.types.UInt32(time), new ru.bitcoin.node.common.types.UInt32(bits), new ru.bitcoin.node.common.types.UInt32(nonce));
            if (ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(header, NetworkParametersRegistry.regtest()) == valid) return header;
        }
        throw new AssertionError("Could not create regtest header");
    }
    private List<Request> requests() {
        var result=new ArrayList<Request>();
        for(String raw:List.of("", "{", "{} {}", "null", "true", "0", "\"text\"", "[]", "{}",
                "{\"method\":null}","{\"method\":12}","{\"jsonrpc\":3}","{\"jsonrpc\":\"3.0\",\"id\":7}",
                "{\"id\":1,\"method\":\"missing_rpc_method\"}",
                "{\"id\":1,\"method\":\"getblockcount\",\"params\":true}",
                "{\"id\":1,\"method\":\"getblockcount\",\"params\":null}",
                "{\"id\":1,\"method\":\"getblockcount\",\"method\":\"missing_rpc_method\"}",
                "{\"id\":null,\"id\":8,\"method\":\"getblockcount\"}",
                "{\"id\":1,\"method\":\"getblockcount\",\"params\":{\"args\":[]}}"))
            result.add(new Request("POST","/",AUTH,raw));
        for(String version:List.of("", "\"jsonrpc\":null,", "\"jsonrpc\":\"1.0\",", "\"jsonrpc\":\"2.0\",")) {
            for(String id:List.of("", "\"id\":null,", "\"id\":true,", "\"id\":[1,2],", "\"id\":{\"a\":1},", "\"id\":1e0,", "\"id\":999999999999999999999999999,")) {
                for(String method:List.of("getblockcount","missing_rpc_method"))
                    result.add(new Request("POST","/",AUTH,"{"+version+id+"\"method\":\""+method+"\"}"));
            }
        }
        for(String raw:List.of("[1]","[{},1]","[{\"jsonrpc\":\"2.0\",\"method\":\"getblockcount\"},1]",
                "[{\"id\":9,\"method\":\"getblockcount\"},true]",
                "[{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"getblockcount\"},true]",
                "[{\"jsonrpc\":\"2.0\",\"method\":\"getblockcount\"},{\"id\":2,\"method\":\"getblockcount\"}]",
                "{\"jsonrpc\":\"2.0\"}","{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":false}"))
            result.add(new Request("POST","/",AUTH,raw));
        for(String verb:List.of("GET","PUT","DELETE","OPTIONS","HEAD"))
            for(String auth:Arrays.asList(null,AUTH)) result.add(new Request(verb,"/",auth,"{}"));
        for(String path:List.of("/missing","/?x=1","/wallet/test","//"))
            result.add(new Request("POST",path,AUTH,"{\"id\":1,\"method\":\"getblockcount\"}"));
        for(String auth:Arrays.asList(null,"Basic bad","basic "+AUTH.substring(6),AUTH+"  ","Basic   "+AUTH.substring(6)))
            result.add(new Request("POST","/",auth,"{}"));
        for(String raw:List.of("{\"id\":1e309,\"method\":\"getblockcount\"}",
                "{\"id\":-0.00e-3,\"method\":\"getblockcount\"}",
                "{\"method\":null,\"method\":\"getblockcount\"}",
                "{\"jsonrpc\":null,\"jsonrpc\":\"2.0\",\"method\":\"getblockcount\"}",
                "{\"method\":\"getblockcount\",\"params\":{\"args\":false}}",
                "{\"method\":\"getblockcount\",\"params\":{\"z\":1,\"a\":2}}"))
            result.add(new Request("POST","/",AUTH,raw));
        for(int depth:List.of(510,511,512,513,1001))
            result.add(new Request("POST","/",AUTH,"{\"method\":\"getblockcount\",\"unused\":"+"[".repeat(depth)+"0"+"]".repeat(depth)+"}"));
        result.add(new Request("POST", "/", AUTH, "{\"id\":1,\"method\":\"help\",\"params\":[\"missing_rpc_method\"]}"));
        for (String raw : List.of(
                "{\"id\":1,\"method\":\"getblockhash\",\"params\":{\"height\":0,\"height\":1}}",
                "{\"id\":1,\"method\":\"getblockhash\",\"params\":{\"args\":[0]}}",
                "{\"id\":1,\"method\":\"getblockhash\",\"params\":{\"args\":[0],\"height\":0,\"junk\":1}}",
                "{\"id\":1,\"method\":\"getblock\",\"params\":{\"verbose\":false}}",
                "{\"id\":1,\"method\":\"getblock\",\"params\":{\"verbose\":false,\"verbosity\":0}}",
                "{\"id\":1,\"method\":\"getrawtransaction\",\"params\":{\"verbose\":false}}",
                "{\"id\":1,\"method\":\"estimatesmartfee\",\"params\":{\"conf_target\":2,\"fee_rate_estimator\":\"mempool_policy\"}}",
                "{\"id\":1,\"method\":\"estimatesmartfee\",\"params\":{\"conf_target\":2,\"fee_rate_estimator\":\"auto\",\"options\":null}}",
                "{\"id\":1,\"method\":\"gettxspendingprevout\",\"params\":{\"outputs\":[],\"return_spending_tx\":true}}",
                "{\"id\":1,\"method\":\"gettxspendingprevout\",\"params\":{\"outputs\":[],\"return_spending_tx\":true,\"options\":{}}}",
                "{\"id\":1,\"method\":\"dumptxoutset\",\"params\":{\"path\":null,\"in_memory\":false,\"options\":{}}}"))
            result.add(new Request("POST", "/", AUTH, raw));
        return result;
    }
    private Reply send(HttpClient client,URI base,Request request) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+request.path)).timeout(java.time.Duration.ofSeconds(10));
        if(request.auth!=null)builder.header("Authorization",request.auth);
        var response=client.send(builder.method(request.verb,HttpRequest.BodyPublishers.ofString(request.body)).build(),HttpResponse.BodyHandlers.ofString());
        Object body;
        try{body=json.readValue(response.body(),Object.class);}catch(RuntimeException ignored){body=response.body();}
        return new Reply(response.statusCode(),response.headers().firstValue("content-type").orElse(""),response.headers().firstValue("www-authenticate").orElse(""),body);
    }
}

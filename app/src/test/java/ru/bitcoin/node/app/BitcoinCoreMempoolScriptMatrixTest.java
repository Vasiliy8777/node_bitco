package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.SchnorrSignature;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.TaprootSignatureHash;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Strict-policy native differential: full responses and partial-acceptance state. */
class BitcoinCoreMempoolScriptMatrixTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final byte[] INTERNAL = hex("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9");
    private static final long AMOUNT = 100_000;

    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void strictScriptProbeSubmissionCapsAndParentRetentionMatchCore() throws Exception {
        runMatrix(false);
    }
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void memoryPressureEvictionAndPartialAcceptanceMatchCore() throws Exception {
        runMatrix(true);
    }
    private void runMatrix(boolean pressure) throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var cases = pressure ? java.util.stream.IntStream.range(0,1100)
                .mapToObj(i -> i==1099 ? wsh("pressure-heavy","75".repeat(99)+"51",Collections.nCopies(99,new byte[80]))
                        : wsh("pressure-"+i,"51",List.of())).toList() : cases();
        var coreData = Files.createDirectory(directory.resolve("strict-core")); int port;
        try (var socket = new ServerSocket(0)) { port=socket.getLocalPort(); }
        var process = new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+coreData,
                "-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0",
                "-acceptnonstdtxn=0","-maxmempool="+(pressure?1:300),"-limitclustersize="+(pressure?5:101),
                "-fallbackfee=0.00002","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI core = URI.create("http://127.0.0.1:"+port);
        try (var client = HttpClient.newHttpClient()) {
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { result(client,core,"getblockcount",List.of()); break; }
                catch (Exception error) { if (!process.isAlive() || System.nanoTime()>deadline) throw error; Thread.sleep(100); }
            }
            result(client,core,"createwallet",List.of("oracle"));
            String address = result(client,core,"getnewaddress",List.of()).toString();
            result(client,core,"generatetoaddress",List.of(101,address));
            var seconds=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()/1000);
            var clock=new java.time.Clock() {
                public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
                public java.time.Clock withZone(java.time.ZoneId zone){Objects.requireNonNull(zone);return this;}
                public java.time.Instant instant(){return java.time.Instant.ofEpochSecond(seconds.get());}
            };
            var pool = new Mempool(new MempoolPolicy(),pressure?new MempoolLimits(64,5000,1000000,336*3600L,100):MempoolLimits.DEFAULT,clock);
            try (var db = new RocksDbDatabase(directory.resolve("java")); var peers = new PeerManager();
                 var validation = new NodeValidationService(db,parameters,seconds::get,pool)) {
                for (int height=1;height<=101;height++) {
                    var hash = result(client,core,"getblockhash",List.of(height));
                    validation.processBlock(BlockParser.parse(hex(result(client,core,"getblock",List.of(hash,0)).toString())));
                }
                var sync = new NodeSyncInfrastructure(db,parameters,()->System.currentTimeMillis()/1000);
                try (var relay = new NodeRelayService(validation,sync,peers)) {
                    var mining = new MiningController(validation,relay,parameters,()->true,new byte[]{0x51},4_000_000,new FeeRate(1));
                    try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1",0),"test","test-password",mining,validation,relay,sync,()->true,peers)) {
                        URI local = URI.create("http://127.0.0.1:"+rpc.port());
                        result(client,core,"syncwithvalidationinterfacequeue",List.of());
                        var coin = (Map<?,?>)((List<?>)result(client,core,"listunspent",List.of())).getFirst();
                        long coinAmount = new BigDecimal(coin.get("amount").toString()).movePointRight(8).longValueExact();
                        byte[] payout = hex(((Map<?,?>)result(client,core,"getaddressinfo",List.of(address))).get("scriptPubKey").toString());
                        byte[] rootScript = wsh("root","51",List.of()).scriptPubKey;
                        var outputs = new ArrayList<TxOut>();
                        for (var test : cases) outputs.add(new TxOut(AMOUNT,test.scriptPubKey));
                        for (int i=0;i<cases.size();i++) outputs.add(new TxOut(AMOUNT,rootScript));
                        outputs.add(new TxOut(coinAmount-AMOUNT*(2*cases.size())-10_000,payout));
                        var unsigned = new Transaction(2,List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex(coin.get("txid").toString()),
                                new UInt32(((Number)coin.get("vout")).longValue())),new byte[0],TxIn.FINAL_SEQUENCE)),outputs,new UInt32(0));
                        var signed = (Map<?,?>)result(client,core,"signrawtransactionwithwallet",List.of(raw(unsigned)));
                        assertEquals(true,signed.get("complete"));
                        var funding = TransactionParser.parse(hex(signed.get("hex").toString()));
                        // Consensus-valid funding block intentionally includes nonstandard outputs.
                        // No permissive mempool policy is used to bypass the rules under test.
                        var parent = validation.activeTip();
                        var coinbase = CoinbaseBuilder.build(102,parameters,10_000,payout,new byte[]{0x01},List.of(funding));
                        var transactions = List.of(coinbase,funding); var root = MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList());
                        Block block = null;
                        for (int nonce=0;nonce<100_000;nonce++) {
                            var header = new BlockHeader(0x20000000,parent.hash(),root,new UInt32(parent.header().timestamp().value()+1),parent.header().bits(),new UInt32(nonce));
                            if (ProofOfWork.isValid(header,parameters)) { block=new Block(header,transactions); break; }
                        }
                        assertNotNull(block);
                        assertNull(result(client,core,"submitblock",List.of(HexFormat.of().formatHex(BlockSerializer.serialize(block)))));
                        validation.processBlock(block); assertEquals(102,validation.activeTip().height());
                        var errors = new ArrayList<String>(); int comparisons=0;
                        var standard=truthSpend(funding,0,payout);
                        var structural = new ArrayList<Transaction>();
                        for (int version : new int[]{0,4}) structural.add(new Transaction(version,standard.inputs(),standard.outputs(),standard.lockTime()));
                        for (byte[] sig : List.of(new byte[]{0x76},new byte[1651])) {
                            var input=standard.inputs().getFirst();
                            structural.add(new Transaction(2,List.of(new TxIn(input.previousOutput(),sig,input.sequence(),input.witness())),standard.outputs(),standard.lockTime()));
                        }
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(98000,new byte[]{0x00})),standard.lockTime()));
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(98000,concat(new byte[]{0x6a},new byte[100001]))),standard.lockTime()));
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(-1,payout)),standard.lockTime()));
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(2100000000000001L,payout)),standard.lockTime()));
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(2100000000000000L,payout),new TxOut(2100000000000000L,payout)),standard.lockTime()));
                        structural.add(new Transaction(2,List.of(standard.inputs().getFirst(),standard.inputs().getFirst()),standard.outputs(),standard.lockTime()));
                        structural.add(new Transaction(2,standard.inputs(),List.of(),standard.lockTime()));
                        for(byte[] sig : List.of(new byte[0],new byte[]{1,1})) structural.add(new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0xffffffffL)),sig,TxIn.FINAL_SEQUENCE)),standard.outputs(),standard.lockTime()));
                        structural.add(new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0xffffffffL)),new byte[]{1,1},TxIn.FINAL_SEQUENCE),standard.inputs().getFirst()),standard.outputs(),standard.lockTime()));
                        structural.add(new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(1)),new byte[0],new UInt32(0))),standard.outputs(),new UInt32(10000000)));
                        structural.add(new Transaction(2,List.of(new TxIn(standard.inputs().getFirst().previousOutput(),new byte[0],new UInt32(5000),standard.inputs().getFirst().witness())),List.of(new TxOut(150000,payout)),new UInt32(0)));
                        structural.add(new Transaction(2,standard.inputs(),List.of(new TxOut(150000,payout)),new UInt32(0)));
                        structural.add(new Transaction(2,List.of(new TxIn(new OutPoint(coinbase.txId(),new UInt32(0)),new byte[0],TxIn.FINAL_SEQUENCE)),standard.outputs(),new UInt32(0)));
                        structural.add(new Transaction(2,List.of(new TxIn(standard.inputs().getFirst().previousOutput(),new byte[]{0x76},TxIn.FINAL_SEQUENCE),new TxIn(new OutPoint(funding.txId(),new UInt32(1)),new byte[1651],TxIn.FINAL_SEQUENCE)),standard.outputs(),new UInt32(0)));
                        for (int i=0;i<structural.size();i++) {
                            comparisons += compare(client,core,local,"testmempoolaccept",List.of(List.of(raw(structural.get(i))),0),"structural "+i,errors);
                            comparisons += compare(client,core,local,"sendrawtransaction",List.of(raw(structural.get(i)),0),"structural broadcast "+i,errors);
                            comparisons += compare(client,core,local,"submitpackage",List.of(List.of(raw(structural.get(i))),0),"structural submission "+i,errors);
                        }
                        // Complete every probe before allowing any member to enter either pool.
                        for (int i=0;i<cases.size();i++) {
                            var test = cases.get(i);
                            var spend = test.spend(new OutPoint(funding.txId(),new UInt32(i)),AMOUNT,payout,2);
                            comparisons += compare(client,core,local,"testmempoolaccept",List.of(List.of(raw(spend)),0),test.name+" single probe",errors);
                            var pack = packageFor(test,funding,cases.size()+i,payout,2,2);
                            comparisons += compare(client,core,local,"testmempoolaccept",List.of(pack,0),test.name+" package probe",errors);
                            for (var versions : List.of(new int[]{3,3},new int[]{3,2},new int[]{2,3})) {
                                var variant=packageFor(test,funding,cases.size()+i,payout,versions[0],versions[1]);
                                comparisons += compare(client,core,local,"testmempoolaccept",List.of(variant,0),
                                        test.name+" versions "+versions[0]+"/"+versions[1],errors);
                            }
                        }
                        for (int i=0;i<cases.size();i++) {
                            var test=cases.get(i); var pack=packageFor(test,funding,cases.size()+i,payout,2,2);
                            comparisons += compare(client,core,local,"submitpackage",List.of(pack,new BigDecimal("0.00000001")),test.name+" client cap",errors);
                            comparisons += compare(client,core,local,"submitpackage",List.of(pack,0),test.name+" partial submission",errors);
                            var expected = new TreeSet<>((List<String>)result(client,core,"getrawmempool",List.of()));
                            var actual = new TreeSet<>((List<String>)result(client,local,"getrawmempool",List.of()));
                            if (!expected.equals(actual)) errors.add(test.name+" pool membership expected count="+expected.size()+" actual count="+actual.size());
                            long expectedUsage=((Number)((Map<?,?>)result(client,core,"getmempoolinfo",List.of())).get("usage")).longValue();
                            if (expectedUsage!=pool.dynamicMemoryUsage()) errors.add(test.name+" memory expected="+expectedUsage+" actual="+pool.dynamicMemoryUsage());
                            comparisons += compare(client,core,local,"submitpackage",List.of(pack,new BigDecimal("0.00000001")),test.name+" known/new metadata",errors);
                            if(pressure && i==475) {
                                var weak=cases.getFirst().spend(new OutPoint(funding.txId(),new UInt32(0)),AMOUNT,payout,2);
                                weak=new Transaction(2,weak.inputs(),List.of(new TxOut(AMOUNT-1000,payout)),new UInt32(0));
                                comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(weak),0),"capacity weak arrival",errors);
                                var heavy=cases.getLast().spend(new OutPoint(funding.txId(),new UInt32(1099)),AMOUNT,payout,2);
                                long heavyFee=20*ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(heavy));
                                heavy=new Transaction(2,heavy.inputs(),List.of(new TxOut(AMOUNT-heavyFee,payout)),new UInt32(0));
                                comparisons+=compare(client,core,local,"testmempoolaccept",List.of(List.of(raw(heavy)),0),"capacity probe does not evict",errors);
                                long evictionRevision=validation.revision();
                                comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(heavy),0),"capacity rejected arrival removes old weak entry",errors);
                                if(validation.revision()<=evictionRevision) errors.add("failed capacity admission did not notify mining revision");
                                compareMemory(client,core,pool,"capacity rejected arrival",errors);
                            }
                        }
                        // Confirm the whole pool, then exercise reuse of the retained array
                        // and replacements with more than one input/output.
                        if (!pressure) {
                            var originalPack=packageFor(cases.getFirst(),funding,cases.size(),payout,2,2);
                            var parentId=TransactionParser.parse(hex(originalPack.getFirst())).txId().toDisplayHex();
                            for (long delta : new long[]{100,-100}) {
                                comparisons+=compare(client,core,local,"prioritisetransaction",List.of(parentId,0,delta),"dirty cluster memory "+delta,errors);
                                compareMemory(client,core,pool,"dirty cluster memory "+delta,errors);
                            }
                        }
                        result(client,core,"generatetoaddress",List.of(1,address));
                        var minedHash=result(client,core,"getblockhash",List.of(103));
                        validation.processBlock(BlockParser.parse(hex(result(client,core,"getblock",List.of(minedHash,0)).toString())));
                        compareMemory(client,core,pool,"confirmed pool",errors);
                        var first=cases.get(0).spend(new OutPoint(funding.txId(),new UInt32(0)),AMOUNT,payout,2);
                        var second=cases.get(1).spend(new OutPoint(funding.txId(),new UInt32(1)),AMOUNT,payout,2);
                        for (int count=1;count<=4;count++) {
                            var values=new ArrayList<TxOut>();
                            long total=200000-10000L*count;
                            for (int output=0;output<count;output++) values.add(new TxOut(total/count+(output==0?total%count:0),payout));
                            var replacement=new Transaction(2,List.of(first.inputs().getFirst(),second.inputs().getFirst()),values,new UInt32(0));
                            comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(replacement),0),"memory RBF "+count,errors);
                            compareMemory(client,core,pool,"memory RBF "+count,errors);
                            for (long delta : new long[]{100,-100}) {
                                comparisons+=compare(client,core,local,"prioritisetransaction",List.of(replacement.txId().toDisplayHex(),0,delta),"memory delta "+count+"/"+delta,errors);
                                compareMemory(client,core,pool,"memory delta "+count+"/"+delta,errors);
                            }
                        }
                        if (!pressure) {
                            assertEquals(1,pool.size());
                            var source=pool.entries().getFirst().transaction();
                            var branches=new ArrayList<TxOut>();
                            for(int i=0;i<70;i++) branches.add(new TxOut(400,rootScript));
                            var unsignedParent=new Transaction(2,List.of(new TxIn(new OutPoint(source.txId(),new UInt32(0)),new byte[0],TxIn.FINAL_SEQUENCE)),branches,new UInt32(0));
                            result(client,core,"syncwithvalidationinterfacequeue",List.of());
                            var signedParent=(Map<?,?>)result(client,core,"signrawtransactionwithwallet",List.of(raw(unsignedParent)));
                            assertEquals(true,signedParent.get("complete"));
                            var resurrected=TransactionParser.parse(hex(signedParent.get("hex").toString()));
                            comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(resurrected),0),"reorg parent",errors);
                            result(client,core,"generatetoaddress",List.of(1,address));
                            var detachedHash=result(client,core,"getblockhash",List.of(104)).toString();
                            validation.processBlock(BlockParser.parse(hex(result(client,core,"getblock",List.of(detachedHash,0)).toString())));
                            for(int i=0;i<70;i++) {
                                var child=new Transaction(2,List.of(new TxIn(new OutPoint(resurrected.txId(),new UInt32(i)),new byte[0],TxIn.FINAL_SEQUENCE,new Witness(List.of(new byte[]{0x51})))),List.of(new TxOut(390-i,payout)),new UInt32(0));
                                comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(child),0),"reorg child "+i,errors);
                            }
                            assertEquals(70,pool.size());
                            result(client,core,"invalidateblock",List.of(detachedHash));
                            validation.invalidateBlock(Hash256.fromDisplayHex(detachedHash));
                            var expected=new TreeSet<>((List<String>)result(client,core,"getrawmempool",List.of()));
                            var actual=new TreeSet<>((List<String>)result(client,local,"getrawmempool",List.of()));
                            assertEquals(64,expected.size(),"Native fixture must trim the oversized reorg cluster");
                            if(!expected.equals(actual)) errors.add("oversized reorg membership expected="+expected+" actual="+actual);
                            compareMemory(client,core,pool,"oversized reorg",errors);
                            seconds.addAndGet(400*3600L);
                            result(client,core,"setmocktime",List.of(seconds.get()));
                            var expiredExpected=new TreeSet<>((List<String>)result(client,core,"getrawmempool",List.of()));
                            var expiredActual=new TreeSet<>((List<String>)result(client,local,"getrawmempool",List.of()));
                            if(!expiredExpected.equals(expiredActual)) errors.add("read expired pool changed membership");
                            comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(structural.get(6)),0),"invalid admission does not expire",errors);
                            var known=pool.entries().isEmpty()?resurrected:pool.entries().getFirst().transaction();
                            comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(known),0),"known broadcast does not expire",errors);
                            result(client,core,"syncwithvalidationinterfacequeue",List.of());
                            var mature=(Map<?,?>)((List<?>)result(client,core,"listunspent",List.of(100))).getFirst();
                            long matureAmount=new BigDecimal(mature.get("amount").toString()).movePointRight(8).longValueExact();
                            var independent=new Transaction(2,List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex(mature.get("txid").toString()),new UInt32(((Number)mature.get("vout")).longValue())),new byte[0],TxIn.FINAL_SEQUENCE)),List.of(new TxOut(matureAmount-10000,payout)),new UInt32(0));
                            var signedIndependent=(Map<?,?>)result(client,core,"signrawtransactionwithwallet",List.of(raw(independent)));
                            assertEquals(true,signedIndependent.get("complete"));
                            var fresh=TransactionParser.parse(hex(signedIndependent.get("hex").toString()));
                            var expiryProbe=(List<?>)result(client,core,"testmempoolaccept",List.of(List.of(raw(fresh)),0));
                            assertEquals(true,((Map<?,?>)expiryProbe.getFirst()).get("allowed"),expiryProbe.toString());
                            comparisons+=compare(client,core,local,"testmempoolaccept",List.of(List.of(raw(fresh)),0),"probe does not expire old family",errors);
                            long revision=validation.revision();
                            comparisons+=compare(client,core,local,"sendrawtransaction",List.of(raw(fresh),0),"finalization expires old family",errors);
                            assertEquals(List.of(fresh.txId().toDisplayHex()),result(client,core,"getrawmempool",List.of()));
                            if(pool.size()!=1 || !pool.contains(fresh.txId())) errors.add("finalization did not expire old family");
                            if(validation.revision()<=revision) errors.add("failed admission with expiry did not notify mining revision");
                            compareMemory(client,core,pool,"expired finalized pool",errors);
                        }
                        System.out.println("CORE32_SCRIPT_PACKAGE_MATRIX pressure="+pressure+" cases="+cases.size()+" comparisons="+comparisons+" discrepancies="+errors.size());
                        for (var error : errors) System.out.println("CORE_MATRIX_DIFFERENCE "+error);
                        assertTrue(errors.isEmpty(),String.join("\n",errors.stream().limit(12).toList()));
                    }
                }
            }
        } finally {
            try (var client=HttpClient.newHttpClient()) { result(client,core,"stop",List.of()); } catch (Exception ignored) { }
            if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
        }
    }

    private List<String> packageFor(ScriptCase test,Transaction funding,int index,byte[] payout,int parentVersion,int childVersion) {
        var parent = new Transaction(parentVersion,List.of(new TxIn(new OutPoint(funding.txId(),new UInt32(index)),new byte[0],TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))),List.of(new TxOut(AMOUNT-2000,test.scriptPubKey)),new UInt32(0));
        var child = test.spend(new OutPoint(parent.txId(),new UInt32(0)),AMOUNT-2000,payout,childVersion);
        return List.of(raw(parent),raw(child));
    }
    private Transaction truthSpend(Transaction funding,int index,byte[] payout) {
        return cases().getFirst().spend(new OutPoint(funding.txId(),new UInt32(index)),AMOUNT,payout,2);
    }
    private void compareMemory(HttpClient client,URI core,Mempool pool,String label,List<String> errors) throws Exception {
        long expected=((Number)((Map<?,?>)result(client,core,"getmempoolinfo",List.of())).get("usage")).longValue();
        long actual=pool.dynamicMemoryUsage();
        if (expected!=actual) errors.add(label+" memory expected="+expected+" actual="+actual);
    }
    private int compare(HttpClient client,URI core,URI local,String method,Object params,String label,List<String> errors) throws Exception {
        var expected=response(client,core,method,params); var actual=response(client,local,method,params);
        if (!expected.equals(actual)) errors.add(label+" expected="+expected+" actual="+actual);
        return 1;
    }
    private Map<?,?> response(HttpClient client,URI uri,String method,Object params) throws Exception {
        var request=HttpRequest.newBuilder(uri).header("Authorization","Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        var value=json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
        // Core emits a null result alongside errors; local JSON-RPC 1.0 does too.
        return value;
    }
    private Object result(HttpClient client,URI uri,String method,Object params) throws Exception {
        var value=response(client,uri,method,params);
        if (value.get("error")!=null) throw new IllegalStateException(method+": "+value);
        return value.get("result");
    }
    private record ScriptCase(String name,byte[] scriptPubKey,byte[] scriptSig,List<byte[]> witness,byte[] signedLeaf) {
        Transaction spend(OutPoint input,long amount,byte[] destination,int version) {
            var items=new ArrayList<>(witness);
            var transaction=new Transaction(version,List.of(new TxIn(input,scriptSig,TxIn.FINAL_SEQUENCE,new Witness(items))),List.of(new TxOut(amount-2000,destination)),new UInt32(0));
            if (signedLeaf!=null) {
                var hash=TaprootSignatureHash.calculate(transaction,0,List.of(new TxOut(amount,scriptPubKey)),0,null,signedLeaf,0xffff_ffffL);
                items.set(0,sign(hash));
                transaction=new Transaction(version,List.of(new TxIn(input,scriptSig,TxIn.FINAL_SEQUENCE,new Witness(items))),transaction.outputs(),transaction.lockTime());
            }
            return transaction;
        }
    }
    private static List<ScriptCase> cases() {
        var result=new ArrayList<ScriptCase>(); var scripts=new LinkedHashMap<String,String>();
        String key="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
        scripts.put("ok","51"); scripts.put("eval-false","00"); scripts.put("verify","0069"); scripts.put("equalverify","515288");
        scripts.put("numequalverify","51529d"); scripts.put("checksigverify","0021"+key+"ad");
        scripts.put("checkmultisigverify","00005121"+key+"51af"); scripts.put("bad-opcode","ff");
        scripts.put("disabled","00637e6851"); scripts.put("stack","75"); scripts.put("altstack","6c"); scripts.put("op-return","6a");
        scripts.put("conditional","5163"); scripts.put("cltv-negative","4fb1"); scripts.put("cltv-unsatisfied","51b1");
        scripts.put("csv-negative","4fb2"); scripts.put("csv-unsatisfied","51b2"); scripts.put("scriptnum","0501000000008b");
        scripts.put("nonminimal-number","0201008b"); scripts.put("nonminimal-push","4c0101"); scripts.put("minimal-if","020100635168");
        scripts.put("nulldummy","51005121"+key+"51ae"); scripts.put("nop-upgrade","b351"); scripts.put("pubkey","000101ac");
        scripts.put("pubkey-count","4fae"); scripts.put("sig-der","010121"+key+"ac"); scripts.put("op-count","61".repeat(202)+"51");
        scripts.put("stack-size","51".repeat(1001)); scripts.put("script-size","61".repeat(10001)+"51");
        for (var entry:scripts.entrySet()) {
            result.add(wsh("wsh-"+entry.getKey(),entry.getValue(),List.of()));
            if (entry.getValue().length()/2<=520) result.add(p2sh("p2sh-"+entry.getKey(),entry.getValue()));
        }
        var truth=wsh("truth","51",List.of());
        result.add(new ScriptCase("wsh-empty",truth.scriptPubKey,new byte[0],List.of(),null));
        result.add(new ScriptCase("wsh-hash",truth.scriptPubKey,new byte[0],List.of(new byte[]{0}),null));
        result.add(new ScriptCase("wsh-malleated",truth.scriptPubKey,new byte[]{0x51},truth.witness,null));
        result.add(wsh("wsh-item-81","7551",List.of(new byte[81])));
        result.add(wsh("wsh-sigops-limit","ae".repeat(800),List.of()));
        result.add(wsh("wsh-sigops-exceeded","ae".repeat(801),List.of()));
        result.add(new ScriptCase("bare-sigops-limit",hex("ac".repeat(2500)),new byte[0],List.of(),null));
        result.add(new ScriptCase("bare-sigops-exceeded",hex("ac".repeat(2501)),new byte[0],List.of(),null));
        result.add(new ScriptCase("witness-v0-length",hex("0015"+"01".repeat(21)),new byte[0],List.of(new byte[]{1}),null));
        result.add(new ScriptCase("witness-unknown",hex("5220"+"01".repeat(32)),new byte[0],List.of(),null));
        for (var entry:Map.of("ok","51","eval-false","00","cleanstack","5151","multisig","ae","minimal-if","020100635168",
                "empty-key","0000ac","pubkey-upgrade","510101ac","op-success","50","checksig-empty","0020"+HexFormat.of().formatHex(INTERNAL)+"ac").entrySet())
            result.add(tap("tap-"+entry.getKey(),entry.getValue(),0xc0,List.of(),false));
        result.add(tap("tap-leaf-upgrade","51",0xc2,List.of(),false));
        var tap=tap("tap","51",0xc0,List.of(),false);
        result.add(new ScriptCase("tap-key-empty",tap.scriptPubKey,new byte[0],List.of(new byte[0]),null));
        result.add(new ScriptCase("tap-key-size",tap.scriptPubKey,new byte[0],List.of(new byte[63]),null));
        result.add(new ScriptCase("tap-key-hashtype",tap.scriptPubKey,new byte[0],List.of(new byte[65]),null));
        result.add(new ScriptCase("tap-key-signature",tap.scriptPubKey,new byte[0],List.of(new byte[64]),null));
        result.add(new ScriptCase("tap-control-size",tap.scriptPubKey,new byte[0],List.of(new byte[]{0x51},new byte[]{(byte)0xc0}),null));
        var wrongControl=tap.witness.getLast().clone(); wrongControl[0]^=1;
        result.add(new ScriptCase("tap-control-commitment",tap.scriptPubKey,new byte[0],List.of(new byte[]{0x51},wrongControl),null));
        var annex=new ArrayList<>(tap.witness); annex.add(new byte[]{0x50,1});
        result.add(new ScriptCase("tap-annex",tap.scriptPubKey,new byte[0],annex,null));
        result.add(tap("tap-valid-signature","20"+HexFormat.of().formatHex(INTERNAL)+"ac",0xc0,List.of(new byte[64]),true));
        result.add(tap("tap-signature-budget",("7620"+HexFormat.of().formatHex(INTERNAL)+"ad").repeat(12)+"7551",0xc0,List.of(new byte[64]),true));
        return List.copyOf(result);
    }
    private static ScriptCase wsh(String name,String script,List<byte[]> initial) {
        var bytes=hex(script); var witness=new ArrayList<>(initial); witness.add(bytes);
        return new ScriptCase(name,concat(hex("0020"),Sha256.hash(bytes)),new byte[0],witness,null);
    }
    private static ScriptCase p2sh(String name,String script) {
        var bytes=hex(script); var sig=new ByteArrayOutputStream(); push(sig,bytes);
        return new ScriptCase(name,concat(hex("a914"),Hash160.hash(bytes),hex("87")),sig.toByteArray(),List.of(),null);
    }
    private static ScriptCase tap(String name,String script,int version,List<byte[]> initial,boolean sign) {
        var bytes=hex(script); var leaf=TaggedHash.hash("TapLeaf",new byte[]{(byte)version},CompactSize.encode(bytes.length),bytes);
        var tweak=new BigInteger(1,TaggedHash.hash("TapTweak",INTERNAL,leaf));
        var point=SchnorrSignature.liftX(INTERNAL).add(Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
        var control=new byte[33]; control[0]=(byte)(version|(point.getAffineYCoord().toBigInteger().testBit(0)?1:0));
        System.arraycopy(INTERNAL,0,control,1,32); var witness=new ArrayList<>(initial); witness.add(bytes); witness.add(control);
        return new ScriptCase(name,concat(hex("5120"),point.getAffineXCoord().getEncoded()),new byte[0],witness,sign?leaf:null);
    }
    private static byte[] sign(byte[] hash) {
        BigInteger nonce=BigInteger.valueOf(7); var point=Secp256k1.DOMAIN.getG().multiply(nonce).normalize();
        if (point.getAffineYCoord().toBigInteger().testBit(0)) nonce=Secp256k1.N.subtract(nonce);
        var r=point.getAffineXCoord().getEncoded(); var e=new BigInteger(1,TaggedHash.hash("BIP0340/challenge",r,INTERNAL,hash)).mod(Secp256k1.N);
        return concat(r,org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32,nonce.add(e.multiply(BigInteger.valueOf(3))).mod(Secp256k1.N)));
    }
    private static void push(ByteArrayOutputStream out,byte[] value) {
        if (value.length<76) out.write(value.length); else if(value.length<=255) {out.write(76);out.write(value.length);}
        else {out.write(77);out.write(value.length&255);out.write(value.length>>>8);} out.writeBytes(value);
    }
    private static byte[] concat(byte[]... parts) {var out=new ByteArrayOutputStream();for(var part:parts)out.writeBytes(part);return out.toByteArray();}
    private static byte[] hex(String value) {return HexFormat.of().parseHex(value);}
    private static String raw(Transaction value) {return HexFormat.of().formatHex(TransactionSerializer.serialize(value));}
}

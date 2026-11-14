package ru.bitcoin.node.app;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.app.rpc.*;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreScriptRenderingTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    @ParameterizedTest @ValueSource(strings={"main","test","regtest"})
    @EnabledIfSystemProperty(named="bitcoin.core.binary", matches=".+")
    void fullScriptAndDecodeResultsMatchCore(String network) throws Exception {
        var parameters = switch (network) { case "main" -> NetworkParametersRegistry.mainnet(); case "test" -> NetworkParametersRegistry.testnet(); default -> NetworkParametersRegistry.regtest(); };
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path data = Files.createDirectory(directory.resolve(network));
        var core = new ProcessBuilder(System.getProperty("bitcoin.core.binary"), "-chain="+network, "-datadir="+data,
                "-server", "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0", "-disablewallet=1",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve(network+".log").toFile()).start();
        URI reference = URI.create("http://127.0.0.1:"+port);
        try (var client = HttpClient.newHttpClient()) {
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { if (call(client, reference, "getblockcount", List.of()).get("error") == null) break; }
                catch (Exception error) { if (!core.isAlive()) throw error; }
                if (System.nanoTime() > deadline) fail("Core startup"); Thread.sleep(50);
            }
            try (var db = new RocksDbDatabase(directory.resolve("java-"+network));
                 var validation = new NodeValidationService(db, parameters, () -> System.currentTimeMillis()/1000, new Mempool())) {
                var sync = new NodeSyncInfrastructure(db, parameters, () -> System.currentTimeMillis()/1000);
                var mining = new MiningController(validation, null, parameters, () -> false, new byte[]{0x51}, 4000000, new FeeRate(1));
                try (var rpc = new NodeRpcServer(new InetSocketAddress("127.0.0.1",0), "test", "test-password", mining, validation, null, sync, () -> false)) {
                    URI local = URI.create("http://127.0.0.1:"+rpc.port()); int comparisons = 0;int utilities=0;
                    for (byte[] script : scripts()) {
                        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(1)), script, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(100, script)), new UInt32(0));
                        String raw = HexFormat.of().formatHex(TransactionSerializer.serialize(tx));
                        var expected = call(client, reference, "decoderawtransaction", List.of(raw));
                        assertEquals(expected, call(client, local, "decoderawtransaction", List.of(raw)), "script="+HexFormat.of().formatHex(script)); comparisons++;
                        String scriptHex=HexFormat.of().formatHex(script);
                        assertEquals(call(client,reference,"decodescript",List.of(scriptHex)),call(client,local,"decodescript",List.of(scriptHex)),"decodescript="+scriptHex);utilities++;
                        var decoded=(Map<?,?>)expected.get("result");var output=(Map<?,?>)((List<?>)decoded.get("vout")).getFirst();var scriptJson=(Map<?,?>)output.get("scriptPubKey");
                        if(scriptJson.get("address") instanceof String address){
                            assertEquals(call(client,reference,"validateaddress",List.of(address)),call(client,local,"validateaddress",List.of(address)),address);utilities++;
                            String descriptor="addr("+address+")";var information=call(client,reference,"getdescriptorinfo",List.of(descriptor));assertEquals(information,call(client,local,"getdescriptorinfo",List.of(descriptor)));utilities++;
                            String checksummed=((Map<?,?>)information.get("result")).get("descriptor").toString();assertEquals(call(client,reference,"deriveaddresses",List.of(checksummed)),call(client,local,"deriveaddresses",List.of(checksummed)));utilities++;
                        }

                    }
                    for (String raw : List.of("", "x", "0", "00", "02000000000000000000", "0200000000010000000000000000015100000000"))
                        for (Object params : List.of(List.of(raw), List.of(raw, true), List.of(raw, false))) {
                            assertEquals(call(client, reference, "decoderawtransaction", params), call(client, local, "decoderawtransaction", params), "raw="+raw+" params="+params); comparisons++;
                        }
                    for(int version:List.of(0,Integer.MAX_VALUE,Integer.MIN_VALUE,-1)){
                        var tx=new Transaction(version,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(1)),new byte[0],TxIn.FINAL_SEQUENCE)),List.of(new TxOut(1,new byte[]{0x51})),new UInt32(0));
                        String raw=HexFormat.of().formatHex(TransactionSerializer.serialize(tx));
                        assertEquals(call(client,reference,"decoderawtransaction",List.of(raw)),call(client,local,"decoderawtransaction",List.of(raw)),"Unsigned transaction version "+version);comparisons++;
                    }
                    for(String method:List.of("getdescriptorinfo","deriveaddresses","validateaddress","decodescript"))for(Object argument:Arrays.asList("","invalid"," ",null,0,true,List.of(),Map.of())){
                        var args=Collections.singletonList(argument);assertEquals(call(client,reference,method,args),call(client,local,method,args),method+" "+args);utilities++;
                    }
                    System.out.println("CORE32_UTILITY_HTTP network="+network+" comparisons="+utilities+" discrepancies=0");
                    System.out.println("CORE32_SCRIPT_RENDER network="+network+" comparisons="+comparisons+" discrepancies=0");
                    int creators = 0;
                    var differences = new ArrayList<String>();
                    for (List<?> parametersForCreation : creationCases(parameters)) {
                        for (String method : List.of("createrawtransaction", "createpsbt")) {
                            compareCreator(client, reference, local, method, parametersForCreation, differences); creators++;
                        }
                        for (Object version : List.of(0, 1, 2, 3, -1, 2.0, 4294967296L)) {
                            var args = new ArrayList<Object>(parametersForCreation);
                            while (args.size() < 5) args.add(null);
                            args.add(version);
                            compareCreator(client, reference, local, "createpsbt", args, differences); creators++;
                        }
                    }
                    for (byte[] script : scripts()) {
                        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(1)), script, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(100, script)), new UInt32(0));
                        String raw = HexFormat.of().formatHex(TransactionSerializer.serialize(tx));
                        for (List<?> args : List.of(List.of(raw), List.of(raw, true), List.of(raw, true, false, 0), List.of(raw, true, true, 2))) {
                            compareCreator(client, reference, local, "converttopsbt", args, differences); creators++;
                        }
                    }
                    for (String raw : List.of("", "0", "zz", "02000000000000000000", "0200000000010000000000000000015100000000"))
                        for (Object version : List.of(0, 2, 1, -1, 2.0)) for (Boolean witness : Arrays.asList(null, false, true)) {
                            var args = Arrays.asList(raw, true, witness, version);
                            compareCreator(client, reference, local, "converttopsbt", args, differences); creators++;
                        }
                    String oneInput = "[{\"txid\":\""+"01".repeat(32)+"\",\"vout\":0}]";
                    for (String outputs : List.of("{\"data\":\"01\",\"data\":\"02\"}", "[{\"data\":\"01\",\"data\":\"02\"}]",
                            "{\"data\":\"xx\",\"data\":\"01\"}", "[{\"data\":\"01\"},{\"data\":\"02\"}]"))
                        for (String method : List.of("createrawtransaction", "createpsbt")) {
                            String body = "{\"id\":1,\"method\":\""+method+"\",\"params\":["+oneInput+","+outputs+"]}";
                            assertEquals(callRaw(client, reference, body), callRaw(client, local, body), body); creators++;
                        }
                    var witnessTx = new Transaction(3, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(1)), new byte[]{0x51}, new UInt32(42), new Witness(List.of(new byte[]{1,2}, new byte[0])))), List.of(new TxOut(100, new byte[]{0x51})), new UInt32(999));
                    String witnessRaw = HexFormat.of().formatHex(TransactionSerializer.serialize(witnessTx));
                    for (boolean permit : List.of(false, true)) for (Boolean witness : Arrays.asList(null, false, true)) for (int version : List.of(0,2)) {
                        compareCreator(client, reference, local, "converttopsbt", Arrays.asList(witnessRaw, permit, witness, version), differences); creators++;
                    }
                    assertTrue(differences.isEmpty(), String.join("\n", differences));
                    System.out.println("CORE32_TX_CREATORS network="+network+" comparisons="+creators+" discrepancies=0");
                    int multisig = 0;
                    for (List<?> args : multisigCases()) {
                        compareCreator(client, reference, local, "createmultisig", args, differences); multisig++;
                    }
                    assertTrue(differences.isEmpty(), String.join("\n", differences));
                    System.out.println("CORE32_MULTISIG network="+network+" comparisons="+multisig+" discrepancies=0");
                    int messages = compareMessages(client, reference, local, parameters, differences);
                    assertTrue(differences.isEmpty(), String.join("\n", differences));
                    System.out.println("CORE32_MESSAGES network="+network+" comparisons="+messages+" discrepancies=0");
                    int psbtRpc=0;
                    for(int version:List.of(0,2)){
                        var args=Arrays.asList(List.of(Map.of("txid","01".repeat(32),"vout",0)),Map.of("data","0102"),100,true,2,version);
                        String psbt=call(client,reference,"createpsbt",args).get("result").toString();
                        compareCreator(client,reference,local,"decodepsbt",List.of(psbt),differences);psbtRpc++;
                        compareCreator(client,reference,local,"analyzepsbt",List.of(psbt),differences);psbtRpc++;
                        for(Boolean extract:Arrays.asList(null,false,true)){compareCreator(client,reference,local,"finalizepsbt",Arrays.asList(psbt,extract),differences);psbtRpc++;}
                        compareCreator(client,reference,local,"combinepsbt",List.of(List.of(psbt,psbt)),differences);psbtRpc++;
                        compareCreator(client,reference,local,"joinpsbts",List.of(List.of(psbt,psbt)),differences);psbtRpc++;
                    }
                    for(String psbt:List.of("", "invalid", "cHNidP8A"))for(String method:List.of("decodepsbt","combinepsbt","joinpsbts","finalizepsbt","analyzepsbt")){
                        Object args=(method.equals("decodepsbt")||method.equals("finalizepsbt")||method.equals("analyzepsbt"))?List.of(psbt):List.of(List.of(psbt,psbt));
                        compareCreator(client,reference,local,method,args,differences);psbtRpc++;
                    }
                    for(Object argument:Arrays.asList(null,1,false,List.of(),Map.of())){compareCreator(client,reference,local,"finalizepsbt",Collections.singletonList(argument),differences);psbtRpc++;}
                    for(Object argument:Arrays.asList(null,1,false,List.of(),Map.of())){compareCreator(client,reference,local,"analyzepsbt",Collections.singletonList(argument),differences);psbtRpc++;}
                    compareCreator(client,reference,local,"finalizepsbt",List.of("invalid",1),differences);psbtRpc++;
                    compareCreator(client,reference,local,"finalizepsbt",Map.of("psbt","invalid","extract",false),differences);psbtRpc++;
                    assertTrue(differences.isEmpty(),String.join("\n",differences));
                    System.out.println("CORE32_PSBT_HTTP network="+network+" comparisons="+psbtRpc+" discrepancies=0");
                }
            }
        } finally {
            try (var client = HttpClient.newHttpClient()) { call(client, reference, "stop", List.of()); } catch (Exception ignored) { }
            if (!core.waitFor(15,TimeUnit.SECONDS)) { core.destroyForcibly(); core.waitFor(5,TimeUnit.SECONDS); }
        }
    }
    private Map<?,?> call(HttpClient client, URI endpoint, String method, Object params) throws Exception {
        return callRaw(client, endpoint, json.writeValueAsString(Map.of("id",1,"method",method,"params",params)));
    }
    private Map<?,?> callRaw(HttpClient client, URI endpoint, String body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(15)).header("Authorization",AUTH)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }

    private void compareCreator(HttpClient client, URI core, URI local, String method, Object args, List<String> differences) throws Exception {
        var expected = call(client, core, method, args); var actual = call(client, local, method, args);
        if (!expected.equals(actual) && differences.size() < 20) {
            String details = method + " " + args + " expected=" + expected + " actual=" + actual;
            differences.add(details.substring(0, Math.min(details.length(), 2000)));
        }
    }
    private List<byte[]> scripts() {
        var scripts = new ArrayList<byte[]>(); scripts.add(new byte[0]);
        for (int opcode = 0; opcode < 256; opcode++) scripts.add(new byte[]{(byte)opcode});
        for (int length : List.of(0,1,2,4,5,75,76,255,256,520,521,10001)) {
            byte[] data = new byte[length]; for (int i=0;i<length;i++) data[i]=(byte)i;
            var out = new java.io.ByteArrayOutputStream();
            if (length < 76) out.write(length); else if (length <= 255) { out.write(0x4c); out.write(length); }
            else { out.write(0x4d); out.write(length & 255); out.write(length >>> 8); }
            out.writeBytes(data); scripts.add(out.toByteArray());
        }
        for (int version=0;version<=16;version++) for (int length : List.of(1,2,20,32,40,41)) {
            byte[] script = new byte[length+2]; script[0]=(byte)(version==0?0:0x50+version);script[1]=(byte)length;
            for(int i=2;i<script.length;i++)script[i]=(byte)(i-2);scripts.add(script);
        }
        String compressed="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
        for(int hashType : List.of(0,1,2,3,4,0x21,0x80,0x81,0x82,0x83)) scripts.add(HexFormat.of().parseHex("093006020101020101"+String.format("%02x",hashType)));
        String uncompressed="0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8";
        for (int total : List.of(1,16,17,18,19,20,21)) for (int required : List.of(1,16,17,20,21)) {
            String threshold = required <= 16 ? String.format("%02x", 0x50+required) : String.format("01%02x", required);
            String count = total <= 16 ? String.format("%02x", 0x50+total) : String.format("01%02x", total);
            scripts.add(HexFormat.of().parseHex(threshold+("21"+compressed).repeat(total)+count+"ae"));
        }
        for (String required : List.of("0101","4c0101","021100","4c0111","0181"))
            scripts.add(HexFormat.of().parseHex(required+("21"+compressed).repeat(20)+"0114ae"));
        for(String hex:List.of("76a914"+"00".repeat(20)+"88ac","a914"+"00".repeat(20)+"87","51204e"+"00".repeat(31),"51024e73",
                "21"+compressed+"ac","41"+uncompressed+"ac","41"+"06"+uncompressed.substring(2)+"ac",
                "21"+"02"+"00".repeat(32)+"ac","5121"+compressed+"51ae","0021"+compressed+"51ae","0000ae",
                "6a00","6a50","6a51","6a4c00","6a4c01","4c0101","4f61b1b2baff")) scripts.add(HexFormat.of().parseHex(hex));
        return scripts;
    }

    private List<List<?>> creationCases(NetworkParameters network) {
        var cases = new ArrayList<List<?>>();
        String hash = "0123456789abcdef".repeat(4);
        var input = Map.of("txid", hash, "vout", 1);
        Object output = Map.of("data", "01020304");
        cases.add(List.of(List.of(), List.of())); cases.add(List.of(List.of(input), output));
        cases.add(List.of(Collections.nCopies(253, input), Map.of("data", "00")));
        var manyOutputs = new LinkedHashMap<String,Object>();
        for (int i = 0; i < 253; i++) {
            byte[] program = new byte[20]; program[0]=(byte)i;
            manyOutputs.put(ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.witness(0, program, network), i);
        }
        cases.add(List.of(List.of(input), manyOutputs));
        cases.add(List.of(List.of(input, input), List.of(output))); cases.add(Arrays.asList(List.of(input), null));
        for (Object lock : Arrays.asList(null, 0, 1, 4294967295L, -1, 4294967296L, 0.0, 1.5, "1"))
            for (Object rbf : Arrays.asList(null, false, true))
                for (Object version : Arrays.asList(null, 0, 1, 2, 3, 4, -1, 2.0, 4294967295L, 4294967296L))
                    cases.add(Arrays.asList(List.of(input), output, lock, rbf, version));
        for (Object sequence : Arrays.asList(null, -1, 0, 4294967293L, 4294967294L, 4294967295L, 4294967296L, "x", true, 1.0))
            for (Boolean rbf : Arrays.asList(null, false, true)) {
                var in = new LinkedHashMap<String,Object>(input); in.put("sequence", sequence);
                cases.add(Arrays.asList(List.of(in), output, 100, rbf));
            }
        for (Object in : Arrays.asList(null, "x", 1, false, List.of(), Map.of(), Map.of("txid", hash), Map.of("txid", "x", "vout", -1)))
            cases.add(List.of(Collections.singletonList(in), output));
        for (Object txid : Arrays.asList(null, 1, false, "", "00", "g".repeat(64), "a".repeat(64), "я".repeat(32)))
            cases.add(List.of(List.of(new HashMap<>() {{ put("txid", txid); put("vout", 0); }}), output));
        for (Object vout : Arrays.asList(null, -1, 0, 2147483647L, 2147483648L, 1.0, true, "1"))
            cases.add(List.of(List.of(new HashMap<>() {{ put("txid", hash); put("vout", vout); }}), output));
        for (Object out : Arrays.asList(null, true, "x", 1, List.of("x"), List.of(Map.of()), List.of(Map.of("data", "00", "other", 1)),
                List.of(Map.of("data", "00"), Map.of("data", "01")))) cases.add(Arrays.asList(List.of(input), out));
        for (Object data : Arrays.asList(null, true, false, 0, 1234, "", "0", "xx", "01", "01".repeat(75), "01".repeat(76), "01".repeat(256), "01".repeat(65536)))
            cases.add(List.of(List.of(input), Collections.singletonMap("data", data)));
        var addresses = new ArrayList<String>();
        boolean main = network.network() == BitcoinNetwork.MAINNET;
        addresses.add(ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main ? 0 : 111, new byte[20]));
        addresses.add(ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main ? 5 : 196, new byte[20]));
        for (int version = 0; version <= 16; version++) for (int length : List.of(2,20,32,40)) {
            if (version == 0 && length != 20 && length != 32) continue;
            addresses.add(ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.witness(version, new byte[length], network));
        }
        addresses.add("invalid"); addresses.add("\u0000");
        for (String address : addresses) {
            cases.add(List.of(List.of(input), Map.of(address, "0.00000001")));
            cases.add(List.of(List.of(input), List.of(Map.of(address, 1), Map.of(address.toUpperCase(Locale.ROOT), 2))));
            cases.add(List.of(List.of(input), Map.of(" "+address+"\t", 1)));
        }
        for (Object amount : Arrays.asList(null, true, List.of(), -1, 21000000, 21000001, 0.000000001, "01", "1e-8", "1e-9", "-0", "1.000000000", "1e999999999"))
            cases.add(List.of(List.of(input), Collections.singletonMap(addresses.getFirst(), amount)));
        return cases;
    }

    private List<List<?>> multisigCases() {
        String compressed = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
        String uncompressed = "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8";
        var cases = new ArrayList<List<?>>();
        for (String key : List.of(compressed, uncompressed, "06"+uncompressed.substring(2)))
            for (int count = 0; count <= 21; count++)
                for (Object required : List.of(-1,0,1,2,16,17,20,21,2147483648L,1.0))
                    for (String type : List.of("legacy","p2sh-segwit","bech32","bech32m","unknown"))
                        cases.add(List.of(required, Collections.nCopies(count, key), type));
        for (Object key : Arrays.asList(null, 1, false, List.of(), Map.of(), "", "xx", "0", "00", "02"+"00".repeat(32), "07"+uncompressed.substring(2), compressed.toUpperCase(Locale.ROOT)))
            cases.add(List.of(1, Collections.singletonList(key)));
        cases.add(List.of(2,List.of(compressed,"03"+compressed.substring(2))));
        cases.add(List.of(2,List.of(compressed,uncompressed),"bech32"));
        return cases;
    }

    private int compareMessages(HttpClient client, URI reference, URI local, NetworkParameters network, List<String> differences) throws Exception {
        int comparisons = 0; boolean main = network.network() == BitcoinNetwork.MAINNET;
        byte[] firstSignature = null; String firstAddress = null;
        // Publicly known synthetic keys only; no wallet or user key is read.
        for (java.math.BigInteger scalar : List.of(java.math.BigInteger.ONE, java.math.BigInteger.TWO, java.math.BigInteger.valueOf(17), ru.bitcoin.node.crypto.secp256k1.Secp256k1.N.subtract(java.math.BigInteger.ONE)))
            for (boolean compressed : List.of(false, true)) {
                var key = new ru.bitcoin.node.crypto.secp256k1.PrivateKey(scalar);
                byte[] payload = Arrays.copyOf(key.bytes(), compressed ? 33 : 32); if (compressed) payload[32] = 1;
                String wif = ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main ? 128 : 239, payload);
                var publicKey = ru.bitcoin.node.crypto.secp256k1.Secp256k1.publicKey(key);
                String address = ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main ? 0 : 111,
                        ru.bitcoin.node.crypto.hash.Hash160.hash(compressed ? publicKey.compressed() : publicKey.uncompressed()));
                for (String message : List.of("", "hello", "привет 🌍", "\u0000\r\n\t", "x".repeat(252), "x".repeat(253), "x".repeat(65536))) {
                    var args = List.of(wif, message);
                    var expected = call(client, reference, "signmessagewithprivkey", args);
                    assertEquals(expected, call(client, local, "signmessagewithprivkey", args), "Synthetic key/message signature"); comparisons++;
                    String signature = expected.get("result").toString();
                    compareCreator(client, reference, local, "verifymessage", List.of(address, signature, message), differences); comparisons++;
                    compareCreator(client, reference, local, "verifymessage", List.of(address, signature, message+"!"), differences); comparisons++;
                    compareCreator(client, reference, local, "signmessagewithprivkey", List.of(" \t"+wif+"\r\n", message), differences); comparisons++;
                    if (firstSignature == null && message.equals("hello")) { firstSignature = Base64.getDecoder().decode(signature); firstAddress = address; }
                    byte[] high = Base64.getDecoder().decode(signature); high[0]=(byte)(27+(((high[0]&255)-27)^1));
                    byte[] s = ru.bitcoin.node.crypto.secp256k1.Secp256k1.N.subtract(new java.math.BigInteger(1,Arrays.copyOfRange(high,33,65))).toByteArray();
                    Arrays.fill(high,33,65,(byte)0); int size=Math.min(32,s.length);System.arraycopy(s,s.length-size,high,65-size,size);
                    compareCreator(client, reference, local, "verifymessage", List.of(address, Base64.getEncoder().encodeToString(high), message), differences); comparisons++;
                }
            }
        for (int header=0;header<256;header++) {
            byte[] altered=firstSignature.clone(); altered[0]=(byte)header;
            compareCreator(client,reference,local,"verifymessage",List.of(firstAddress,Base64.getEncoder().encodeToString(altered),"hello"),differences);comparisons++;
        }
        for (String signature : List.of("", "a", "AA", "AAA", "AA==", "AB==", "AAA=", "AAAA", "====", "AA===", " AAA=", "AAA=\n", "AAA-", "AAA_", "я", "\u0000", Base64.getEncoder().encodeToString(Arrays.copyOf(firstSignature,64))))
            for (String address : List.of(firstAddress, " "+firstAddress+"\t", "invalid", ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main?5:196,new byte[20]),
                    ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.witness(0,new byte[20],network))) {
                compareCreator(client,reference,local,"verifymessage",List.of(address,signature,"hello"),differences);comparisons++;
            }
        for (String wif : List.of("", "x", "01", "\u0000", ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main?239:128,new byte[32]),
                ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main?128:239,new byte[32]),
                ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main?128:239,ru.bitcoin.node.crypto.secp256k1.Secp256k1.N.toByteArray()),
                ru.bitcoin.node.protocol.address.BitcoinAddressEncoder.legacy(main?128:239,new byte[33]))) {
            compareCreator(client,reference,local,"signmessagewithprivkey",List.of(wif,"hello"),differences);comparisons++;
        }
        return comparisons;
    }
}

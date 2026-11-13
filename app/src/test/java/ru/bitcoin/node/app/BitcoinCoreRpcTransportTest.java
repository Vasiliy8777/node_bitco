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
    private record Reply(int status,String contentType,String challenge,Object body) { }
    private record Request(String verb,String path,String auth,String body) { }
    private static final String AUTH="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(StandardCharsets.UTF_8));

    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void transportParserBatchAndNotificationsMatchCore() throws Exception {
        Path data=Files.createDirectory(directory.resolve("core"));int port;
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var process=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,
                "-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1",
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
                var validation=new NodeValidationService(db,parameters,()->System.currentTimeMillis()/1000,new Mempool())) {
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
                    }
                }
            }
        } finally {
            try(var client=HttpClient.newHttpClient()){send(client,core,new Request("POST","/",AUTH,"{\"id\":1,\"method\":\"stop\"}"));}
            catch(Exception ignored){}
            if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        }
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

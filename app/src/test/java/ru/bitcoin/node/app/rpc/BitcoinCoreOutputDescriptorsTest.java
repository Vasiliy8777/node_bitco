package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreOutputDescriptorsTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void descriptorCorpusCanonicalSchemasErrorsAndAddresses()throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve("core"));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI endpoint=URI.create("http://127.0.0.1:"+port);var network=NetworkParametersRegistry.mainnet();
        try(var client=HttpClient.newHttpClient()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{if(reply(client,endpoint,"getblockcount",List.of()).get("error")==null)break;}catch(Exception error){if(!core.isAlive())throw error;}if(System.nanoTime()>deadline)fail("Core startup");Thread.sleep(50);}
            List<String> corpus;
            try(var stream=getClass().getResourceAsStream("/rpc/core32-descriptors.txt")){assertNotNull(stream);corpus=new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList();}
            var expanded=new LinkedHashSet<String>(corpus);
            for(String descriptor:corpus){
                for(String changed:List.of(" "+descriptor,descriptor+" ","sh("+descriptor+")",descriptor.substring(0,descriptor.length()-1),descriptor+"#",descriptor+"#000000000",descriptor+"##00000000"))expanded.add(changed);
                try{expanded.add(DescriptorChecksum.add(descriptor));expanded.add(descriptor+"#00000000");}catch(IllegalArgumentException invalid){}
                for(int index:List.of(0,1,4,8,16,32,64))if(index<descriptor.length()){var changed=new StringBuilder(descriptor);changed.setCharAt(index,'!');expanded.add(changed.toString());}
            }
            corpus=new ArrayList<>(expanded);
            var differences=new ArrayList<String>();int comparisons=0,derivations=0;var scripts=new LinkedHashSet<String>();
            for(String descriptor:corpus) {
                var expected=reply(client,endpoint,"getdescriptorinfo",List.of(descriptor));Object result=null,error=null;
                try{result=DescriptorRpc.info(descriptor,network);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                catch(RuntimeException invalid){error=Map.of("exception",invalid.getClass().getSimpleName(),"message",Objects.toString(invalid.getMessage()));}
                if((!Objects.equals(expected.get("error"),error)||!Objects.equals(expected.get("result"),result))&&differences.size()<30)
                    differences.add(descriptor+"\nnative="+expected+"\njava="+result+" error="+error);
                if(expected.get("error")==null&&result!=null) {
                    boolean ranged=Boolean.TRUE.equals(((Map<?,?>)expected.get("result")).get("isrange"));
                    String checksummed=DescriptorChecksum.add(descriptor.split("#",-1)[0]);List<?> params=ranged?List.of(checksummed,List.of(0,2)):List.of(checksummed);
                    var addresses=reply(client,endpoint,"deriveaddresses",params);Object actual=null,deriveError=null;
                    try{actual=DescriptorRpc.derive(params,network);}catch(RpcException invalid){deriveError=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                    catch(RuntimeException invalid){deriveError=Map.of("exception",invalid.getClass().getSimpleName(),"message",Objects.toString(invalid.getMessage()));}
                    if((!Objects.equals(addresses.get("error"),deriveError)||!Objects.equals(addresses.get("result"),actual))&&differences.size()<30)
                        differences.add("DERIVE "+descriptor+"\nnative="+addresses+"\njava="+actual+" error="+deriveError);
                    var document=OutputDescriptor.parse(descriptor,false,network);var pending=new ArrayDeque<OutputDescriptor>(document.descriptors());
                    while(!pending.isEmpty()){var child=pending.pop();child.children.forEach(pending::push);var outputs=child.expand(0,document.privateKeys());if(outputs.isPresent())for(byte[] script:outputs.get())scripts.add(HexFormat.of().formatHex(script));}
                    derivations++;
                }
                comparisons++;
            }
            var ranges=new ArrayList<Object>(Arrays.asList(null,false,true,"0",List.of(),List.of(1),List.of(1,2,3),List.of(2,1),List.of(-1,2),List.of(0,-1),List.of(0,1000000),List.of(0,2147483648L),Map.of(),0.5,1.0,Long.MIN_VALUE,Long.MAX_VALUE,new java.math.BigInteger("9223372036854775808"),-1,0,1,List.of(0,1),List.of(2147483647,2147483647)));
            String pub="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
            String extended=ru.bitcoin.node.protocol.address.Bip32Key.fromSeed(new byte[16],true).neuter().encode();
            for(String descriptor:List.of("wpkh("+pub+")","wpkh("+extended+"/*)","invalid",DescriptorChecksum.add("wpkh("+pub+")"),DescriptorChecksum.add("wpkh("+extended+"/*)")))for(Object range:ranges){
                var params=Arrays.asList(descriptor,range);var expected=reply(client,endpoint,"deriveaddresses",params);Object actual=null,error=null;
                try{actual=DescriptorRpc.derive(params,network);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                if((!Objects.equals(expected.get("error"),error)||!Objects.equals(expected.get("result"),actual))&&differences.size()<30)differences.add("RANGE "+params+" native="+expected+" java="+actual+" error="+error);
                comparisons++;
            }
            for(int op=0;op<256;op++){scripts.add(HexFormat.of().formatHex(new byte[]{(byte)op}));scripts.add(HexFormat.of().formatHex(new byte[]{0x51,(byte)op}));}
            for(int length:List.of(0,1,20,32,33,65,75,76,80,255,256,520,521,10000,10001)){byte[] bytes=new byte[length];Arrays.fill(bytes,(byte)0x61);byte[] push=MiniscriptEncoding.push(bytes);scripts.add(HexFormat.of().formatHex(push));if(push.length>0)scripts.add(HexFormat.of().formatHex(Arrays.copyOf(push,push.length-1)));}
            var random=new Random(3201);for(int i=0;i<1000;i++){byte[] script=new byte[random.nextInt(101)];random.nextBytes(script);scripts.add(HexFormat.of().formatHex(script));}
            scripts.addAll(List.of("","0","gg"," ","0X01","aa bb"));int scriptComparisons=0;
            for(String script:scripts){
                var expected=reply(client,endpoint,"decodescript",List.of(script));Object actual=null,error=null;
                try{actual=ScriptDecoding.decode(script,network);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                if((!Objects.equals(expected.get("error"),error)||!Objects.equals(expected.get("result"),actual))&&differences.size()<30)differences.add("SCRIPT "+script+" native="+expected+" java="+actual+" error="+error);
                scriptComparisons++;
            }
            assertTrue(differences.isEmpty(),String.join("\n",differences));
            System.out.println("CORE32_DECODE_SCRIPT comparisons="+scriptComparisons+" discrepancies=0");
            System.out.println("CORE32_OUTPUT_DESCRIPTORS comparisons="+comparisons+" derivations="+derivations+" discrepancies=0");
        } finally {
            try(var client=HttpClient.newHttpClient()){reply(client,endpoint,"stop",List.of());}catch(Exception ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private Map<?,?> reply(HttpClient client,URI endpoint,String method,Object params)throws Exception {
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(20)).header("Authorization",auth)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }
}

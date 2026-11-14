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

class BitcoinCorePsbtUpdaterTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void descriptorMetadataAndUpdaterSemantics()throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve("core"));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI endpoint=URI.create("http://127.0.0.1:"+port);var network=NetworkParametersRegistry.mainnet();
        try(var client=HttpClient.newHttpClient()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{if(reply(client,endpoint,"getblockcount",List.of()).get("error")==null)break;}catch(Exception error){if(!core.isAlive())throw error;}if(System.nanoTime()>deadline)fail("Core startup");Thread.sleep(50);}

            var differences=new ArrayList<String>();int comparisons=0;
            List<String> corpus;try(var stream=getClass().getResourceAsStream("/rpc/core32-descriptors.txt")){corpus=new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList();}
            for(String text:new LinkedHashSet<>(corpus)) {
                OutputDescriptor.Parsed parsed;try{parsed=OutputDescriptor.parse(text,false,network);}catch(RpcException invalid){continue;}
                var descriptors=List.of(Map.of("desc",text,"range",List.of(0,1)));
                for(var descriptor:parsed.descriptors()) {
                    var scripts=descriptor.expand(0,parsed.privateKeys());if(scripts.isEmpty())continue;
                    for(byte[] script:scripts.get())for(int version:List.of(0,2))for(int utxo:List.of(0,1,2)) {
                        var zero=new ru.bitcoin.node.common.types.UInt32(0);
                        var previous=new ru.bitcoin.node.protocol.transaction.Transaction(2,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(new ru.bitcoin.node.protocol.transaction.OutPoint(new ru.bitcoin.node.common.types.Hash256(new byte[32]),zero),new byte[0],zero)),List.of(new ru.bitcoin.node.protocol.transaction.TxOut(10000,script)),zero);
                        var unsigned=new ru.bitcoin.node.protocol.transaction.Transaction(2,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(new ru.bitcoin.node.protocol.transaction.OutPoint(previous.txId(),zero),new byte[0],zero)),List.of(new ru.bitcoin.node.protocol.transaction.TxOut(9000,script)),zero);
                        var doc=PsbtDocument.parse(PsbtCreator.create(unsigned,version));
                        if(utxo==1)doc.inputs.getFirst().put("00",new PsbtDocument.Field(0,new byte[]{0},ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(previous),previous));
                        if(utxo==2){var out=new java.io.ByteArrayOutputStream();out.writeBytes(ru.bitcoin.node.common.bytes.LittleEndian.int64(10000));out.writeBytes(ru.bitcoin.node.common.encoding.CompactSize.encode(script.length));out.writeBytes(script);doc.inputs.getFirst().put("01",new PsbtDocument.Field(1,new byte[]{1},out.toByteArray(),previous.outputs().getFirst()));}
                        String encoded=PsbtCodec.encode(doc);var expected=reply(client,endpoint,"utxoupdatepsbt",List.of(encoded,descriptors));Object actual=null,error=null;
                        try{actual=PsbtUpdater.update(encoded,descriptors,network,PsbtUpdater.EMPTY);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                        if((!Objects.equals(expected.get("error"),error)||!Objects.equals(expected.get("result"),actual))&&differences.size()<15) {
                            Object nativeDecoded=expected.get("result") instanceof String str?PsbtJson.decode(PsbtDocument.parse(str),network):expected;
                            Object javaDecoded=actual instanceof String str?PsbtJson.decode(PsbtDocument.parse(str),network):error;
                            differences.add("DESC "+text+" version="+version+" utxo="+utxo+"\nnative="+nativeDecoded+"\njava="+javaDecoded);
                        }
                        comparisons++;
                        if(expected.get("result") instanceof String updated)for(List<?> supplied:List.of(descriptors,List.of())) {
                            compare(client,endpoint,updated,supplied,network,differences,"REPEAT "+text);comparisons++;
                        }
                        if(utxo==1&&version==2)for(int sighash:List.of(-1,0,1,2,3,128,129,130,131)) {
                            var altered=PsbtDocument.parse(encoded);byte[] value=ru.bitcoin.node.common.bytes.LittleEndian.int32(sighash);
                            altered.inputs.getFirst().put("03",new PsbtDocument.Field(3,new byte[]{3},value,value));
                            compare(client,endpoint,PsbtCodec.encode(altered),descriptors,network,differences,"SIGHASH "+sighash+" "+text);comparisons++;
                        }
                    }
                }
            }
            var badDescriptors=new ArrayList<Object>(Arrays.asList(null,0,true,List.of(),Map.of(),Map.of("desc",0),Map.of("desc",false),Map.of("desc",List.of()),Map.of("desc",Map.of()),Map.of("desc","bad"),Map.of("desc","bad","range",-1),Map.of("desc","bad","range",List.of(2,1))));
            String pub="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
            for(Object range:Arrays.asList(-1,0,1,0.5,true,"0",List.of(),List.of(2,1),List.of(0,1000000),List.of(0,2147483648L)))badDescriptors.add(Map.of("desc","wpkh("+pub+")","range",range));
            for(Object bad:badDescriptors){compare(client,endpoint,"invalid",Collections.singletonList(bad),network,differences,"SCAN "+bad);comparisons++;}
            assertTrue(differences.isEmpty(),String.join("\n",differences));System.out.println("CORE32_PSBT_UPDATER comparisons="+comparisons+" discrepancies=0");
        } finally {
            try(var client=HttpClient.newHttpClient()){reply(client,endpoint,"stop",List.of());}catch(Exception ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private void compare(HttpClient client,URI endpoint,String encoded,List<?> descriptors,ru.bitcoin.node.protocol.network.NetworkParameters network,List<String> differences,String context)throws Exception {
        var expected=reply(client,endpoint,"utxoupdatepsbt",List.of(encoded,descriptors));Object actual=null,error=null;
        try{actual=PsbtUpdater.update(encoded,descriptors,network,PsbtUpdater.EMPTY);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
        if((!Objects.equals(expected.get("result"),actual)||!Objects.equals(expected.get("error"),error))&&differences.size()<15)differences.add(context+" native="+expected+" java="+actual+" error="+error);
    }
    private Map<?,?> reply(HttpClient client,URI endpoint,String method,Object params)throws Exception {
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(20)).header("Authorization",auth)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }
}

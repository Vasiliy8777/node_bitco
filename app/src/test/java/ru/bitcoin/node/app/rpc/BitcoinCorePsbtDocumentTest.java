package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Native malformed-document oracle independent of the Java PSBT implementation. */
class BitcoinCorePsbtDocumentTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private int successfulJoins;
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void parsesCoreDocumentsAndExactErrors() throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve("core"));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI endpoint=URI.create("http://127.0.0.1:"+port);
        try(var client=HttpClient.newHttpClient()){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{if(call(client,endpoint,"getblockcount",List.of()).get("error")==null)break;}catch(Exception error){if(!core.isAlive())throw error;}
                if(System.nanoTime()>deadline)fail("Core startup");Thread.sleep(50);}
            Map<?,?> vectors;
            try(var stream=getClass().getResourceAsStream("/rpc/core32-psbt.json")){assertNotNull(stream);vectors=json.readValue(stream,Map.class);}
            var corpus=new LinkedHashSet<String>();
            try(var stream=getClass().getResourceAsStream("/rpc/bip370.json")){
                var bip370=json.readValue(stream,Map.class);for(Object encoded:(List<?>)bip370.get("vectors"))corpus.add(encoded.toString());
            }
            corpus.addAll(aliasCases(vectors));
            for(Object value:(List<?>)vectors.get("invalid"))corpus.add(value.toString());
            for(Object value:(List<?>)vectors.get("invalid_with_msg"))corpus.add(((List<?>)value).getFirst().toString());
            for(Object value:(List<?>)vectors.get("valid")){
                String encoded=value.toString();corpus.add(encoded);byte[] bytes=Base64.getDecoder().decode(encoded);
                for(int size=0;size<Math.min(bytes.length,80);size++)corpus.add(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes,size)));
                for(int size=Math.max(80,bytes.length-16);size<bytes.length;size++)corpus.add(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes,size)));
                for(int position=0;position<Math.min(bytes.length,96);position++){
                    byte[] changed=bytes.clone();changed[position]^=(byte)0xff;corpus.add(Base64.getEncoder().encodeToString(changed));
                }
                byte[] extended=Arrays.copyOf(bytes,bytes.length+1);corpus.add(Base64.getEncoder().encodeToString(extended));
            }
            corpus.addAll(List.of("", "a", "AA", "AB==", "====", "AA==", "cHNidP8=", "cHNidP8A"));
            var synthetic=new ArrayList<String>();
            for(int version:List.of(0,1,2,3,-1,Integer.MIN_VALUE))for(long vout:List.of(0L,0xffff_ffffL)){
                var tx=new ru.bitcoin.node.protocol.transaction.Transaction(version,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(
                        new ru.bitcoin.node.protocol.transaction.OutPoint(new ru.bitcoin.node.common.types.Hash256(new byte[32]),new ru.bitcoin.node.common.types.UInt32(vout)),new byte[0],new ru.bitcoin.node.common.types.UInt32(0xffff_fffdL))),
                        List.of(new ru.bitcoin.node.protocol.transaction.TxOut(1,new byte[]{0x51})),new ru.bitcoin.node.common.types.UInt32(0));
                synthetic.add(PsbtCreator.create(tx,0));synthetic.add(PsbtCreator.create(tx,2));
            }
            corpus.addAll(synthetic);
            var differences=new ArrayList<String>();int comparisons=0,combinations=0,joins=0,finalizations=0;var valid=new LinkedHashSet<String>();
            for(String encoded:corpus){
                var updated=call(client,endpoint,"utxoupdatepsbt",List.of(encoded));Object localUpdate=null,updateError=null;
                try{localUpdate=PsbtUpdater.update(encoded,List.of(),ru.bitcoin.node.protocol.network.NetworkParametersRegistry.regtest(),PsbtUpdater.EMPTY);}
                catch(RpcException error){updateError=Map.of("code",error.code(),"message",error.getMessage());}
                if((!Objects.equals(updated.get("result"),localUpdate)||!Objects.equals(updated.get("error"),updateError))&&differences.size()<30)
                    differences.add("updater case="+comparisons+" native="+updated+" java="+localUpdate+" error="+updateError);
                var expected=call(client,endpoint,"decodepsbt",List.of(encoded));Map<String,Object> actualError=null;PsbtDocument actual=null;
                try{actual=PsbtDocument.parse(encoded);}catch(RpcException error){actualError=Map.of("code",error.code(),"message",error.getMessage());}
                if(!Objects.equals(expected.get("error"),actualError)&&differences.size()<30)
                    differences.add("case="+comparisons+" encoded="+encoded.substring(0,Math.min(80,encoded.length()))+" native="+expected.get("error")+" java="+actualError);
                if(expected.get("error")==null && actual!=null){var result=(Map<?,?>)expected.get("result");
                    assertEquals(((Number)result.get("psbt_version")).intValue(),actual.version);
                    assertEquals(((List<?>)result.get("inputs")).size(),actual.inputs.size());assertEquals(((List<?>)result.get("outputs")).size(),actual.outputs.size());
                    var rendered=json.readValue(json.writeValueAsString(PsbtJson.decode(actual,ru.bitcoin.node.protocol.network.NetworkParametersRegistry.regtest())),Map.class);
                    String difference=difference(result,rendered,"result");if(difference!=null&&differences.size()<30)differences.add("case="+comparisons+" "+difference);
                    compareCombination(client,endpoint,List.of(encoded),differences);combinations++;
                    var analysis=call(client,endpoint,"analyzepsbt",List.of(encoded));
                    var localAnalysis=json.readValue(json.writeValueAsString(PsbtAnalyzer.analyze(encoded)),Map.class);
                    String analysisDifference=difference(analysis.get("result"),localAnalysis,"analysis");
                    if(analysisDifference!=null&&differences.size()<30)differences.add("analysis case="+comparisons+" "+analysisDifference+" psbt="+encoded);

                    var finalized=call(client,endpoint,"finalizepsbt",List.of(encoded,false));
                    if(finalized.get("error")==null){
                        finalizations++;
                        var local=PsbtDocument.parse(encoded);boolean complete=new PsbtFinalizer(local).finalizeInputs();
                        var nativeFinal=(Map<?,?>)finalized.get("result");
                        if((!Objects.equals(nativeFinal.get("complete"),complete)||!Objects.equals(nativeFinal.get("psbt"),PsbtCodec.encode(local)))&&differences.size()<30)
                            differences.add("finalizer case="+comparisons+" encoded="+encoded+" native="+nativeFinal+" java="+PsbtCodec.encode(local)+" complete="+complete);
                    }

                    if(valid.size()<80)valid.add(encoded);
                }
                comparisons++;
            }
            for(Object vector:(List<?>)vectors.get("combiner")){
                var fixture=(Map<?,?>)vector;compareCombination(client,endpoint,(List<?>)fixture.get("combine"),differences);combinations++;
            }
            var selected=new ArrayList<>(valid);
            for(int first=0;first<selected.size();first++)for(int second=0;second<selected.size();second++){
                compareCombination(client,endpoint,List.of(selected.get(first),selected.get(second)),differences);combinations++;
                compareJoin(client,endpoint,List.of(selected.get(first),selected.get(second)),differences);joins++;
            }
            for(List<?> args:List.of(List.of(),Collections.singletonList(null),List.of(1),List.of(false),List.of(List.of()),List.of("bad",1),List.of(1,"bad"))){compareCombination(client,endpoint,args,differences);combinations++;compareJoin(client,endpoint,args,differences);joins++;}
            for(String encoded:synthetic){compareJoin(client,endpoint,List.of(encoded,encoded),differences);joins++;}
            assertTrue(differences.isEmpty(),String.join("\n",differences));
            assertTrue(successfulJoins>0,"Native join matrix must exercise successful shuffled joins");
            System.out.println("CORE32_PSBT_DOCUMENT_ANALYSIS comparisons="+finalizations+" discrepancies=0");
            System.out.println("CORE32_PSBT_DOCUMENT_FINALIZER comparisons="+finalizations+" discrepancies=0");
            System.out.println("CORE32_PSBT_DOCUMENT comparisons="+comparisons+" discrepancies=0");
            System.out.println("CORE32_PSBT_COMBINE comparisons="+combinations+" discrepancies=0");
            System.out.println("CORE32_PSBT_JOIN comparisons="+joins+" accepted="+successfulJoins+" discrepancies=0");
        }finally{
            try(var client=HttpClient.newHttpClient()){call(client,endpoint,"stop",List.of());}catch(Exception ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private Map<?,?> call(HttpClient client,URI endpoint,String method,Object params)throws Exception{
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(15)).header("Authorization",auth)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }
    private void compareCombination(HttpClient client,URI endpoint,List<?> documents,List<String> differences)throws Exception{
        var expected=call(client,endpoint,"combinepsbt",List.of(documents));Object actual=null;Map<String,Object> error=null;
        try{actual=PsbtCodec.combine(documents);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
        if((!Objects.equals(expected.get("error"),error)||!Objects.equals(expected.get("result"),actual))&&differences.size()<30){
            String difference="combine nativeError="+expected.get("error")+" javaError="+error+" nativeResult="+expected.get("result")+" javaResult="+actual;
            differences.add(difference.substring(0,Math.min(1600,difference.length())));
        }
    }
    private void compareJoin(HttpClient client,URI endpoint,List<?> documents,List<String> differences)throws Exception{
        var expected=call(client,endpoint,"joinpsbts",List.of(documents));String actual=null;Map<String,Object> error=null;
        try{actual=PsbtCodec.join(documents);}catch(RpcException invalid){error=Map.of("code",invalid.code(),"message",invalid.getMessage());}
        if(!Objects.equals(expected.get("error"),error)){if(differences.size()<30)differences.add("join nativeError="+expected.get("error")+" javaError="+error);return;}
        if(actual!=null){
            successfulJoins++;
            var nativeDecoded=(Map<?,?>)call(client,endpoint,"decodepsbt",List.of(expected.get("result"))).get("result");
            var javaDecoded=json.readValue(json.writeValueAsString(PsbtJson.decode(PsbtDocument.parse(actual),ru.bitcoin.node.protocol.network.NetworkParametersRegistry.regtest())),Map.class);
            String difference=difference(normalizeJoin(nativeDecoded),normalizeJoin(javaDecoded),"join");if(difference!=null&&differences.size()<30)differences.add(difference);
        }
    }
    private Map<String,Object> normalizeJoin(Map<?,?> decoded)throws Exception{
        var result=new TreeMap<String,Object>();decoded.forEach((key,value)->result.put(key.toString(),value));
        var transaction=new TreeMap<String,Object>();((Map<?,?>)result.get("tx")).forEach((key,value)->transaction.put(key.toString(),value));
        transaction.remove("txid");transaction.remove("hash");
        var vin=(List<?>)transaction.remove("vin");var vout=(List<?>)transaction.remove("vout");
        var inputs=(List<?>)result.remove("inputs");var outputs=(List<?>)result.remove("outputs");
        var inputPairs=new ArrayList<String>();for(int index=0;index<vin.size();index++)inputPairs.add(json.writeValueAsString(canonical(List.of(vin.get(index),inputs.get(index)))));
        var outputPairs=new ArrayList<String>();for(int index=0;index<vout.size();index++){
            var coin=new TreeMap<String,Object>();((Map<?,?>)vout.get(index)).forEach((key,value)->coin.put(key.toString(),value));coin.remove("n");outputPairs.add(json.writeValueAsString(canonical(List.of(coin,outputs.get(index)))));
        }
        Collections.sort(inputPairs);Collections.sort(outputPairs);result.put("tx",transaction);result.put("input_pairs",inputPairs);result.put("output_pairs",outputPairs);return result;
    }
    private static Object canonical(Object value){if(value instanceof Map<?,?> object){var result=new TreeMap<String,Object>();object.forEach((key,entry)->result.put(key.toString(),canonical(entry)));return result;}if(value instanceof List<?> array)return array.stream().map(BitcoinCorePsbtDocumentTest::canonical).toList();return value;}
    private List<String> aliasCases(Map<?,?> vectors){
        var cases=new ArrayList<String>();
        var tx=new ru.bitcoin.node.protocol.transaction.Transaction(2,List.of(new ru.bitcoin.node.protocol.transaction.TxIn(
                new ru.bitcoin.node.protocol.transaction.OutPoint(new ru.bitcoin.node.common.types.Hash256(new byte[32]),new ru.bitcoin.node.common.types.UInt32(0)),new byte[0],new ru.bitcoin.node.common.types.UInt32(0xffff_fffdL))),
                List.of(new ru.bitcoin.node.protocol.transaction.TxOut(1,new byte[]{0x51})),new ru.bitcoin.node.common.types.UInt32(0));
        byte[] publicKey=HexFormat.of().parseHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        for(boolean reverse:List.of(false,true)){
            PsbtDocument document=PsbtDocument.parse(PsbtCreator.create(tx,0));var input=document.inputs.getFirst();
            for(int type:List.of(0x1b,0x1c)){
                byte[] shortKey=new byte[67];shortKey[0]=(byte)type;System.arraycopy(publicKey,0,shortKey,1,33);System.arraycopy(publicKey,0,shortKey,34,33);
                byte[] longKey=Arrays.copyOf(shortKey,99),first=new byte[type==0x1b?66:32],second=first.clone();Arrays.fill(second,(byte)1);
                var a=new PsbtDocument.Field(type,shortKey,first,null);var b=new PsbtDocument.Field(type,longKey,second,null);
                for(var field:reverse?List.of(b,a):List.of(a,b))input.put(HexFormat.of().formatHex(field.key()),field);
            }
            byte[] pathKey=new byte[33];pathKey[0]=0x16;System.arraycopy(publicKey,1,pathKey,1,32);
            byte[] path=new byte[1+64+8];path[0]=2;Arrays.fill(path,1,65,(byte)1);path[65]=1;path[66]=2;path[67]=3;path[68]=4;path[69]=1;path[72]=(byte)0x80;
            input.put(HexFormat.of().formatHex(pathKey),new PsbtDocument.Field(0x16,pathKey,path,null));
            cases.add(raw(document));
        }
        for(byte script:List.of((byte)0,(byte)0x51)){
            PsbtDocument document=PsbtDocument.parse(PsbtCreator.create(tx,0));byte[] key=new byte[34];key[0]=0x15;key[1]=(byte)0xc0;System.arraycopy(publicKey,1,key,2,32);
            document.inputs.getFirst().put(HexFormat.of().formatHex(key),new PsbtDocument.Field(0x15,key,new byte[]{script,(byte)0xc0},null));cases.add(raw(document));
        }
        for(Object encoded:(List<?>)vectors.get("valid")){
            PsbtDocument original=PsbtDocument.parse(encoded.toString());var xpub=original.globals.values().stream().filter(field->field.type()==1).findFirst();
            if(xpub.isEmpty())continue;
            for(boolean reverse:List.of(false,true))for(boolean sameOrigin:List.of(false,true)){
                PsbtDocument document=PsbtDocument.parse(encoded.toString());var globals=new PsbtDocument.Fields();var source=xpub.get();byte[] key=source.key().clone(),value=source.value().clone();key[5]^=1;if(!sameOrigin)value[0]^=1;
                var alias=new PsbtDocument.Field(1,key,value,null);if(reverse)globals.put(HexFormat.of().formatHex(key),alias);globals.putAll(document.globals);if(!reverse)globals.put(HexFormat.of().formatHex(key),alias);
                document.globals.clear();document.globals.putAll(globals);cases.add(raw(document));
            }
            break;
        }
        return cases;
    }
    private static String raw(PsbtDocument document){
        var output=new java.io.ByteArrayOutputStream();output.writeBytes(new byte[]{0x70,0x73,0x62,0x74,(byte)0xff});var maps=new ArrayList<PsbtDocument.Fields>();maps.add(document.globals);maps.addAll(document.inputs);maps.addAll(document.outputs);
        for(var fields:maps){for(var field:fields.values()){output.writeBytes(ru.bitcoin.node.common.encoding.CompactSize.encode(field.key().length));output.writeBytes(field.key());output.writeBytes(ru.bitcoin.node.common.encoding.CompactSize.encode(field.value().length));output.writeBytes(field.value());}output.write(0);}
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
    private static String difference(Object expected,Object actual,String path){
        if(Objects.equals(expected,actual))return null;
        if(expected instanceof Map<?,?> e&&actual instanceof Map<?,?> a){
            if(!e.keySet().equals(a.keySet()))return path+" keys native="+e.keySet()+" java="+a.keySet();
            for(Object key:e.keySet()){String difference=difference(e.get(key),a.get(key),path+"."+key);if(difference!=null)return difference;}
        }else if(expected instanceof List<?> e&&actual instanceof List<?> a){
            if(e.size()!=a.size())return path+" lengths native="+e.size()+" java="+a.size();
            for(int i=0;i<e.size();i++){String difference=difference(e.get(i),a.get(i),path+"["+i+"]");if(difference!=null)return difference;}
        }
        return path+" native="+expected+" java="+actual;
    }
}

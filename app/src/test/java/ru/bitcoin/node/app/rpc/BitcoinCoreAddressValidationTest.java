package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.network.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreAddressValidationTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final String CHARSET="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void destinationsAndDetailedErrorLocationsMatchCoreAcrossNetworks()throws Exception {
        Set<String> corpus=corpus();
        for(String chain:List.of("main","test","regtest"))compare(chain,corpus);
    }
    private void compare(String chain,Set<String> corpus)throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve(chain));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-chain="+chain,"-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve(chain+".log").toFile()).start();
        var network=chain.equals("main")?NetworkParametersRegistry.mainnet():chain.equals("test")?NetworkParametersRegistry.testnet():NetworkParametersRegistry.regtest();
        URI endpoint=URI.create("http://127.0.0.1:"+port);
        try(var client=HttpClient.newHttpClient()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{if(reply(client,endpoint,"getblockcount",List.of()).get("error")==null)break;}catch(Exception error){if(!core.isAlive())throw error;}if(System.nanoTime()>deadline)fail("Core startup");Thread.sleep(50);}
            var differences=new ArrayList<String>();int comparisons=0;
            for(String address:corpus) {
                var expected=reply(client,endpoint,"validateaddress",List.of(address));var actual=AddressValidation.validate(address,network);
                if(!Objects.equals(expected.get("result"),actual)&&differences.size()<30)differences.add(json.writeValueAsString(address)+" native="+expected+" java="+actual);
                comparisons++;
            }
            assertTrue(differences.isEmpty(),String.join("\n",differences));
            System.out.println("CORE32_VALIDATE_ADDRESS network="+chain+" comparisons="+comparisons+" discrepancies=0");
        } finally {
            try(var client=HttpClient.newHttpClient()){reply(client,endpoint,"stop",List.of());}catch(Exception ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private Set<String> corpus()throws Exception {
        var addresses=new LinkedHashSet<String>();
        for(String name:List.of("valid","invalid"))try(var stream=getClass().getResourceAsStream("/rpc/core32-key-io-"+name+".json")) {
            assertNotNull(stream);for(Object row:json.readValue(stream,List.class))if(row instanceof List<?> list&&!list.isEmpty()&&list.getFirst() instanceof String text)addresses.add(text);
        }
        addresses.addAll(List.of(""," ","1","bc","bc1","BC1","bcrt","bcqqqqqqq","bc1qqqqqqq","bc1qqqqqqq\0","bc1éqqqqq","bc1😀qqqqq","bc1"+"q".repeat(100),"bc1"+"Q".repeat(100)));
        for(int prefix:List.of(0,5,111,196,128,239))for(int length:List.of(0,1,19,20,21,32,96,97,100,101)) {
            byte[] payload=new byte[length];Arrays.fill(payload,(byte)1);addresses.add(BitcoinAddressEncoder.legacy(prefix,payload));
        }
        var standard=new ArrayList<String>();
        for(var network:List.of(NetworkParametersRegistry.mainnet(),NetworkParametersRegistry.testnet(),NetworkParametersRegistry.regtest()))for(int version:List.of(0,1,2,16))for(int length:List.of(2,20,32,40)) {
            if(version==0&&length!=20&&length!=32)continue;byte[] program=new byte[length];Arrays.fill(program,(byte)3);standard.add(BitcoinAddressEncoder.witness(version,program,network));
        }
        for(var network:List.of(NetworkParametersRegistry.mainnet(),NetworkParametersRegistry.testnet(),NetworkParametersRegistry.regtest()))standard.add(BitcoinAddressEncoder.witness(1,new byte[]{0x4e,0x73},network));
        var random=new Random(173350);
        for(String address:standard) {
            addresses.add(address);addresses.add(address.toUpperCase(Locale.ROOT));addresses.add(" "+address);addresses.add(address+"\t");
            for(int index=0;index<address.length();index++) {
                char original=address.charAt(index);int value=CHARSET.indexOf(original);char changed=CHARSET.charAt((value+8)&31);
                var copy=new StringBuilder(address);copy.setCharAt(index,changed);addresses.add(copy.toString());
                copy=new StringBuilder(address);copy.setCharAt(index,Character.toUpperCase(original));addresses.add(copy.toString());
            }
            int separator=address.lastIndexOf('1');
            for(int count=0;count<50;count++) {
                var copy=new StringBuilder(address);int first=separator+1+random.nextInt(address.length()-separator-1),second;
                do{second=separator+1+random.nextInt(address.length()-separator-1);}while(second==first);
                copy.setCharAt(first,CHARSET.charAt((CHARSET.indexOf(address.charAt(first))+1+random.nextInt(31))&31));
                copy.setCharAt(second,CHARSET.charAt((CHARSET.indexOf(address.charAt(second))+1+random.nextInt(31))&31));addresses.add(copy.toString());
            }
            addresses.add(address.substring(0,separator+2)+"é"+address.substring(separator+3));
            addresses.add(address.substring(0,separator+2)+"😀"+address.substring(separator+3));
        }
        for(String hrp:List.of("bc","tb","bcrt","bcx","bcrtX","tbx"))for(int version:List.of(0,1,2,16,17,31))for(int length:List.of(0,1,2,20,32,40,41))for(int encoding:List.of(1,0x2bc830a3)) {
            byte[] program=new byte[length];Arrays.fill(program,(byte)0x42);var data=new ArrayList<Integer>();data.add(version);int acc=0,bits=0;
            for(byte value:program){acc=acc<<8|(value&255);bits+=8;while(bits>=5){bits-=5;data.add(acc>>>bits&31);}}if(bits>0)data.add(acc<<(5-bits)&31);
            addresses.add(bech32(hrp,data,encoding));
            data.add(1);addresses.add(bech32(hrp,data,encoding));
        }
        addresses.add(bech32("bc",List.of(),1));addresses.add(bech32("bcrt",List.of(),0x2bc830a3));
        return addresses;
    }
    private static String bech32(String hrp,List<Integer> data,int encoding) {
        int[] generators={0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3};var values=new ArrayList<Integer>();
        for(char c:hrp.toCharArray())values.add(c>>>5);values.add(0);for(char c:hrp.toCharArray())values.add(c&31);values.addAll(data);values.addAll(Collections.nCopies(6,0));
        int mod=1;for(int value:values){int top=mod>>>25;mod=(mod&0x1ffffff)<<5^value;for(int i=0;i<5;i++)if((top>>>i&1)!=0)mod^=generators[i];}mod^=encoding;
        var text=new StringBuilder(hrp).append('1');for(int value:data)text.append(CHARSET.charAt(value));for(int i=0;i<6;i++)text.append(CHARSET.charAt(mod>>>(5*(5-i))&31));return text.toString();
    }
    private Map<?,?> reply(HttpClient client,URI endpoint,String method,Object params)throws Exception {
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(20)).header("Authorization",auth)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }
}

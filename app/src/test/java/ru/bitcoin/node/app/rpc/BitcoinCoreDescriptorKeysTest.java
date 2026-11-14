package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.protocol.address.*;
import ru.bitcoin.node.protocol.network.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreDescriptorKeysTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final HexFormat HEX=HexFormat.of();
    private static final NetworkParameters NETWORK=NetworkParametersRegistry.regtest();
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void canonicalKeysDerivationsAndErrorsMatchCore()throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve("core"));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI endpoint=URI.create("http://127.0.0.1:"+port);
        try(var client=HttpClient.newHttpClient()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{if(reply(client,endpoint,"getblockcount",List.of()).get("error")==null)break;}catch(Exception error){if(!core.isAlive())throw error;}if(System.nanoTime()>deadline)fail("Core startup");Thread.sleep(50);}
            var root=Bip32Key.fromSeed(new byte[16],false);String xpub=root.neuter().encode(),xprv=root.encode();
            String pub="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",pub2="02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";
            byte[] scalar=new byte[33];scalar[31]=1;scalar[32]=1;String wif=BitcoinAddressEncoder.legacy(239,scalar);
            var keys=new ArrayList<>(List.of(pub,pub.toUpperCase(Locale.ROOT),pub2,pub.substring(2),"00","00".repeat(32),"", " "+pub,pub+" ",wif,xpub,xprv,
                    "04"+pub.substring(2)+"483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8",
                    "06"+pub.substring(2)+"483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"));
            for(String base:List.of(xpub,xprv))for(String suffix:List.of("/0","/0/1","/0h","/0'/*h","/0h/*'","/<0;1>","/<0;1>/*","/<0;1h>/2","/<0;0>","/<0>","/<0;1>/<2;3>","/2147483648","/4294967296","/-1","/+1","/","/*","/*h","/0/*/1"))keys.add(base+suffix);
            for(String prefix:List.of("[DEADBEEF]","[deadbeef/1h/2']","[deadbeef/<0;1>]","[]","[123]","[zzzzzzzz]","deadbeef]","[deadbeef]]"))for(String base:List.of(pub,xpub+"/1h/*'",xprv+"/<0;1>/*"))keys.add(prefix+base);
            keys.addAll(List.of("musig("+pub+","+pub2+")","musig("+pub2+","+pub+")","musig("+wif+","+pub2+")","musig()",
                    "musig("+pub+")/0","musig("+xpub+","+xprv+")/0/*","musig("+xpub+"/*,"+xpub+")/0",
                    "musig("+xpub+"/0h,"+xprv+")","musig("+xpub+","+xprv+")/<0;1>","musig("+xpub+"/<0;1>,"+xprv+")",
                    "musig("+xpub+"/<0;1>,"+xprv+")/<0;1>","musig("+xpub+","+xprv+")/0h"));
            int comparisons=0;
            for(var context:DescriptorKey.Context.values())for(String expression:keys) {
                if(context==DescriptorKey.Context.MUSIG&&(expression.isEmpty()||expression.startsWith("musig(")))continue;
                String descriptor=wrap(context,expression);var expected=reply(client,endpoint,"getdescriptorinfo",List.of(descriptor));
                List<DescriptorKey> parsed=null;String failure=null;
                try{parsed=DescriptorKey.parse(expression,context,NETWORK);}catch(DescriptorKey.Invalid invalid){failure=prefix(context)+invalid.getMessage();}
                String label=context+" "+expression;
                Object localInfo=null,localError=null;try{localInfo=DescriptorRpc.info(descriptor,NETWORK);}catch(RpcException invalid){localError=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                assertEquals(expected.get("error"),localError,label+" descriptor parser");assertEquals(expected.get("result"),localInfo,label+" descriptor info");

                if(expected.get("error")!=null) {
                    assertNotNull(failure,label);assertEquals(((Map<?,?>)expected.get("error")).get("message"),failure,label);
                } else {
                    assertNull(failure,label);assertNotNull(parsed);var info=(Map<?,?>)expected.get("result");
                    var texts=parsed.stream().map(key->DescriptorChecksum.add(wrap(context,key.text()))).toList();
                    assertEquals(texts.getFirst(),info.get("descriptor"),label);if(texts.size()>1)assertEquals(texts,info.get("multipath_expansion"),label);
                    assertEquals(parsed.getFirst().ranged(),info.get("isrange"),label);assertEquals(parsed.stream().anyMatch(DescriptorKey::hasPrivate),info.get("hasprivatekeys"),label);
                    Object params=parsed.getFirst().ranged()?List.of(DescriptorChecksum.add(descriptor),List.of(0,1)):List.of(DescriptorChecksum.add(descriptor));
                    var addresses=reply(client,endpoint,"deriveaddresses",params);
                    Object localAddresses=null,deriveError=null;try{localAddresses=DescriptorRpc.derive((List<?>)params,NETWORK);}catch(RpcException invalid){deriveError=Map.of("code",invalid.code(),"message",invalid.getMessage());}
                    assertEquals(addresses.get("error"),deriveError,label+" complete derivation error");assertEquals(addresses.get("result"),localAddresses,label+" complete derivation");
                    boolean possible=true;var branches=new ArrayList<List<String>>();
                    for(var key:parsed) {
                        var branch=new ArrayList<String>();for(int index=0;index<=(key.ranged()?1:0);index++) {
                            var material=key.derive(index);if(material.isEmpty()){possible=false;break;}
                            byte[] publicKey=material.get().publicKey();if(context==DescriptorKey.Context.MUSIG)publicKey=MuSig2Aggregation.publicKey(List.of(publicKey));
                            assertNotNull(publicKey,label);branch.add(address(context,publicKey));
                        }
                        branches.add(branch);
                    }
                    assertEquals(possible,addresses.get("error")==null,label+" derivation");
                    if(possible)assertEquals(branches.size()==1?branches.getFirst():branches,addresses.get("result"),label+" addresses");
                }
                comparisons++;
            }
            System.out.println("CORE32_DESCRIPTOR_KEYS comparisons="+comparisons+" discrepancies=0");
        } finally {
            try(var client=HttpClient.newHttpClient()){reply(client,endpoint,"stop",List.of());}catch(Exception ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private static String wrap(DescriptorKey.Context context,String key) {
        return switch(context){case TOP->"pkh("+key+")";case P2SH->"sh(pkh("+key+"))";case P2WPKH->"wpkh("+key+")";case P2WSH->"wsh(pk("+key+"))";case P2TR->"tr("+key+")";case MUSIG->"tr(musig("+key+"))";};
    }
    private static String prefix(DescriptorKey.Context context) { return switch(context){case TOP,P2SH->"pkh(): ";case P2WPKH->"wpkh(): ";case P2WSH->"pk(): ";case P2TR->"tr(): ";case MUSIG->"tr(): musig(): ";}; }
    private static String address(DescriptorKey.Context context,byte[] key) {
        if(context==DescriptorKey.Context.P2TR||context==DescriptorKey.Context.MUSIG) {
            byte[] x=Arrays.copyOfRange(key,1,33);var t=new java.math.BigInteger(1,TaggedHash.hash("TapTweak",x));
            var point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(x).add(ru.bitcoin.node.crypto.secp256k1.Secp256k1.DOMAIN.getG().multiply(t)).normalize();
            return BitcoinAddressEncoder.witness(1,point.getAffineXCoord().getEncoded(),NETWORK);
        }
        if(context==DescriptorKey.Context.P2WPKH)return BitcoinAddressEncoder.witness(0,Hash160.hash(key),NETWORK);
        if(context==DescriptorKey.Context.P2WSH){byte[] script=new byte[key.length+2];script[0]=(byte)key.length;System.arraycopy(key,0,script,1,key.length);script[script.length-1]=(byte)0xac;return BitcoinAddressEncoder.witness(0,Sha256.hash(script),NETWORK);}
        if(context==DescriptorKey.Context.TOP)return BitcoinAddressEncoder.legacy(111,Hash160.hash(key));
        byte[] script=new byte[25];script[0]=0x76;script[1]=(byte)0xa9;script[2]=20;System.arraycopy(Hash160.hash(key),0,script,3,20);script[23]=(byte)0x88;script[24]=(byte)0xac;
        return BitcoinAddressEncoder.legacy(196,Hash160.hash(script));
    }
    private Map<?,?> reply(HttpClient client,URI endpoint,String method,Object params)throws Exception {
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(20)).header("Authorization",auth)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        return json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);
    }
}

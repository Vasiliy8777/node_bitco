package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Native descriptors create scripts/signatures; Java only receives public signing material. */
class BitcoinCoreMiniscriptTest {
    @TempDir Path directory;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final HexFormat HEX=HexFormat.of();
    private int analysisComparisons;
    @Test @EnabledIfSystemProperty(named="bitcoin.core.binary",matches=".+")
    void satisfiesNativeMiniscripts()throws Exception{
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Path data=Files.createDirectory(directory.resolve("core"));
        var core=new ProcessBuilder(System.getProperty("bitcoin.core.binary"),"-regtest","-datadir="+data,"-server","-listen=0","-connect=0","-dnsseed=0","-discover=0","-listenonion=0","-natpmp=0","-disablewallet=1","-rpcuser=test","-rpcpassword=test-password","-rpcport="+port)
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        URI endpoint=URI.create("http://127.0.0.1:"+port);
        try(var client=HttpClient.newHttpClient()){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(true){try{call(client,endpoint,"getblockcount",List.of());break;}catch(Exception|AssertionError error){if(!core.isAlive()||System.nanoTime()>deadline)throw error;Thread.sleep(50);}}
            var privateKeys=new ArrayList<String>();var publicKeys=new ArrayList<String>();
            for(int index=1;index<=3;index++){
                byte[] secret=new byte[33];secret[31]=(byte)index;secret[32]=1;privateKeys.add(BitcoinAddressEncoder.legacy(239,secret));
                var key=ru.bitcoin.node.crypto.secp256k1.PrivateKey.fromBytes(Arrays.copyOf(secret,32));publicKeys.add(HEX.formatHex(ru.bitcoin.node.crypto.secp256k1.Secp256k1.publicKey(key).compressed()));
            }
            byte[] preimage=new byte[32];preimage[31]=9;
            var hashes=Map.of("SHA",Sha256.hash(preimage),"RIPEMD",Ripemd160.hash(preimage),"HASH256",Hash256Digest.hashBytes(preimage),"HASH160",Hash160.hash(preimage));
            var expressions=List.of("pk({A})","pkh({A})","and_v(v:pk({A}),pk({B}))","and_b(pk({A}),a:pk({B}))",
                    "or_b(pk({A}),a:pk({B}))","or_d(pk({A}),pk({B}))","or_i(pk({A}),pk({B}))","andor(pk({A}),pk({B}),pk({C}))",
                    "and_v(or_c(pk({A}),v:pk({B})),1)","thresh(2,pk({A}),s:pk({B}),s:pk({C}))","j:pk({A})","n:pk({A})",
                    "and_v(v:pk({A}),dv:older(5))","and_v(v:older(5),pk({A}))","and_v(v:after(500),pk({A}))",
                    "and_v(v:older(4194309),pk({A}))","and_v(v:after(500000010),pk({A}))","or_i(and_v(v:older(5),pk({A})),pk({B}))",
                    "and_v(v:pk({A}),1)","andor(pk({A}),pk({B}),0)","n:multi(2,{A},{B},{C})",
                    "and_v(v:pk({A}),sha256({SHA}))","and_v(v:pk({A}),ripemd160({RIPEMD}))","and_v(v:pk({A}),hash256({HASH256}))","and_v(v:pk({A}),hash160({HASH160}))");
            var seen=EnumSet.noneOf(MiniscriptTypes.Fragment.class);int comparisons=0,complete=0;
            for(boolean taproot:List.of(false,true)){
            seen.clear();comparisons=0;complete=0;
            for(String expression:expressions){
                String publicDescriptor=descriptor(expression,publicKeys,hashes,taproot);List<?> addresses;
                try{addresses=(List<?>)call(client,endpoint,"deriveaddresses",List.of(DescriptorChecksum.add(publicDescriptor)));}
                catch(AssertionError invalid){throw new AssertionError("Fixture "+expression+": "+invalid.getMessage(),invalid);}
                var address=(Map<?,?>)call(client,endpoint,"validateaddress",List.of(addresses.getFirst()));byte[] scriptPubKey=HEX.parseHex(address.get("scriptPubKey").toString());
                for(int mask=0;mask<8;mask++)for(int psbtVersion:List.of(0,2))for(long[] locks:List.of(new long[]{1000,100},new long[]{0,0},new long[]{500000100,4194560},new long[]{1000,0xffff_ffffL})){
                    var keys=new ArrayList<String>();for(int index=0;index<3;index++)keys.add((mask&(1<<index))!=0?privateKeys.get(index):publicKeys.get(index));
                    var tx=new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0)),new byte[0],new UInt32(locks[1]))),List.of(new TxOut(90000,new byte[]{0x51})),new UInt32(locks[0]));
                    PsbtDocument document=PsbtDocument.parse(PsbtCreator.create(tx,psbtVersion));
                    var coin=new java.io.ByteArrayOutputStream();coin.writeBytes(LittleEndian.int64(100000));coin.writeBytes(CompactSize.encode(scriptPubKey.length));coin.writeBytes(scriptPubKey);
                    document.inputs.getFirst().put("01",new PsbtDocument.Field(1,new byte[]{1},coin.toByteArray(),null));
                    for(var challenge:hashes.entrySet()){
                        int type=switch(challenge.getKey()){case "RIPEMD"->0x0a;case "SHA"->0x0b;case "HASH160"->0x0c;default->0x0d;};
                        byte[] key=new byte[challenge.getValue().length+1];key[0]=(byte)type;System.arraycopy(challenge.getValue(),0,key,1,key.length-1);
                        document.inputs.getFirst().put(HEX.formatHex(key),new PsbtDocument.Field(type,key,preimage,null));
                    }
                    // Use the raw writer: the synthetic witness UTXO is re-parsed by both implementations.
                    String initial=raw(document);String privateDescriptor=DescriptorChecksum.add(descriptor(expression,keys,hashes,taproot));
                    var updated=(Map<?,?>)call(client,endpoint,"descriptorprocesspsbt",Arrays.asList(initial,List.of(privateDescriptor),null,true,false));
                    String prepared=updated.get("psbt").toString();PsbtDocument signed=PsbtDocument.parse(prepared);
                    var finalized=(Map<?,?>)call(client,endpoint,"finalizepsbt",List.of(prepared));boolean expected=Boolean.TRUE.equals(finalized.get("complete"));
                    byte[] script,control=null,leaf=null;
                    if(taproot){
                        var field=signed.inputs.getFirst().values().stream().filter(entry->entry.type()==0x15).findFirst().orElseThrow();
                        script=Arrays.copyOf(field.value(),field.value().length-1);control=Arrays.copyOfRange(field.key(),1,field.key().length);
                        leaf=TaggedHash.hash("TapLeaf",new byte[]{field.value()[field.value().length-1]},CompactSize.encode(script.length),script);
                    }else script=signed.inputs.getFirst().field(5).value();
                    var context=new PsbtMiniscriptContext(signed,0,leaf);Miniscript.Node<String> root=Miniscript.parseScript(script,context);
                    MiniscriptSatisfaction.Plan plan=root==null?null:MiniscriptSatisfaction.satisfy(root,context);
                    if(root!=null){
                        assertArrayEquals(script,MiniscriptEncoding.script(root,context),"Native script round trip "+expression);
                        var limits=MiniscriptLimits.analyze(root,Comparator.naturalOrder());assertTrue(limits.sane()&&limits.satisfiable(),expression+" limits");
                        String text=MiniscriptEncoding.text(root,context);
                        var reparsed=MiniscriptText.parse(text,taproot,key->context.fromPublicKey(HEX.parseHex(key)));
                        assertNotNull(reparsed,text);assertArrayEquals(script,MiniscriptEncoding.script(reparsed,context),"Text parser round trip "+text);
                        String sourceExpression=expression;
                        for(int keyIndex=0;keyIndex<3;keyIndex++)sourceExpression=sourceExpression.replace("{"+(char)('A'+keyIndex)+"}",taproot?publicKeys.get(keyIndex).substring(2):publicKeys.get(keyIndex));
                        for(var challenge:hashes.entrySet())sourceExpression=sourceExpression.replace("{"+challenge.getKey()+"}",HEX.formatHex(challenge.getValue()));
                        if(taproot)sourceExpression=sourceExpression.replace("multi(","multi_a(");
                        var sourceParsed=MiniscriptText.parse(sourceExpression,taproot,key->context.fromPublicKey(HEX.parseHex(key)));
                        assertNotNull(sourceParsed,sourceExpression);assertArrayEquals(script,MiniscriptEncoding.script(sourceParsed,context),"Source parser round trip "+sourceExpression);

                        String expanded=taproot?"tr(50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0,"+text+")":"wsh("+text+")";
                        assertEquals(addresses,call(client,endpoint,"deriveaddresses",List.of(DescriptorChecksum.add(expanded))),"Native text round trip "+text);
                    }
                    if(root!=null){var pending=new ArrayDeque<Miniscript.Node<String>>();pending.push(root);while(!pending.isEmpty()){var node=pending.pop();seen.add(node.fragment);node.children.forEach(pending::push);}}
                    String label=expression+" taproot="+taproot+" mask="+mask+" psbt="+psbtVersion+" locks="+Arrays.toString(locks);
                    assertEquals(finalized,PsbtFinalizer.finish(prepared,true),label+" extracted");
                    compareAnalysis(client,endpoint,prepared,label);
                    var localFinal=PsbtDocument.parse(prepared);assertEquals(expected,new PsbtFinalizer(localFinal).finalizeInputs(),label+" finalizer");
                    var nativeFinal=(Map<?,?>)call(client,endpoint,"finalizepsbt",List.of(prepared,false));assertEquals(nativeFinal.get("psbt"),PsbtCodec.encode(localFinal),label+" final PSBT");
                    if(expected){
                        assertNotNull(plan,label);assertEquals(MiniscriptSatisfaction.Availability.YES,plan.availability,label);
                        var extracted=TransactionParser.parse(HEX.parseHex(finalized.get("hex").toString()));var actual=plan.items();var witness=extracted.inputs().getFirst().witness().items();assertEquals(actual.size()+(taproot?2:1),witness.size(),label);
                        for(int index=0;index<actual.size();index++)assertArrayEquals(witness.get(index),actual.get(index),label+" stack["+index+"]");complete++;
                        ru.bitcoin.node.consensus.transaction.InputScriptValidator.validateKnownInput(extracted,0,List.of(new TxOut(100000,scriptPubKey)),StandardScriptVerifyFlags.STANDARD);
                        assertTrue(new PsbtScriptVerifier(signed).verify(0,extracted.inputs().getFirst().scriptSig(),extracted.inputs().getFirst().witness()),label);
                        ru.bitcoin.node.consensus.transaction.InputScriptValidator.validateKnownInputData(signed.transaction(false).orElseThrow(),0,List.of(new TxOut(100000,scriptPubKey)),extracted.inputs().getFirst().scriptSig(),extracted.inputs().getFirst().witness(),StandardScriptVerifyFlags.STANDARD);
                    }else if(plan!=null&&plan.availability==MiniscriptSatisfaction.Availability.YES){
                        var stack=new ArrayList<>(plan.items());stack.add(script);if(taproot)stack.add(control);Witness candidate=new Witness(stack);boolean valid;
                        Transaction unsigned=signed.transaction(false).orElseThrow();
                        if(taproot){
                            var input=unsigned.inputs().getFirst();var spending=new Transaction(unsigned.version(),List.of(new TxIn(input.previousOutput(),input.scriptSig(),input.sequence(),candidate)),unsigned.outputs(),unsigned.lockTime());
                            try{ru.bitcoin.node.script.validation.TaprootValidator.validate(spending,0,List.of(new TxOut(100000,scriptPubKey)),Arrays.copyOfRange(scriptPubKey,2,34),StandardScriptVerifyFlags.STANDARD);valid=true;}
                            catch(ScriptExecutionException invalid){valid=false;}
                        }else valid=WitnessV0ScriptVerifier.verifyP2wsh(unsigned,0,new byte[0],candidate,WitnessProgram.parse(scriptPubKey).orElseThrow(),100000,StandardScriptVerifyFlags.STANDARD);
                        assertFalse(valid,"Core refuses a satisfaction which Java accepts: "+label);
                    }
                    comparisons++;
                }
            }
            var required=EnumSet.allOf(MiniscriptTypes.Fragment.class);required.remove(taproot?MiniscriptTypes.Fragment.MULTI:MiniscriptTypes.Fragment.MULTI_A);
            assertTrue(complete>20,"Exercise actual signed witnesses");assertEquals(required,seen,"Every fragment for its script context must be decoded");
            System.out.println("CORE32_MINISCRIPT context="+(taproot?"TAPSCRIPT":"WSH")+" comparisons="+comparisons+" complete="+complete+" fragments="+seen.size()+" discrepancies=0");
            }
            compareStandardFinalizers(client,endpoint,privateKeys,publicKeys);
            compareOfflineAmounts(client,endpoint);
            compareMissingCoins(client,endpoint);
            compareAnalysisEdges(client,endpoint);
            compareMiniscriptLimits(client,endpoint,publicKeys);
            try(var stream=getClass().getResourceAsStream("/rpc/musig-finalizer.json")){for(Object vector:json.readValue(stream,List.class)){var fixture=(Map<?,?>)vector;compareAnalysis(client,endpoint,fixture.get("psbt").toString(),fixture.get("label").toString());}}
            System.out.println("CORE32_PSBT_ANALYSIS comparisons="+analysisComparisons+" discrepancies=0");
        }finally{
            try(var client=HttpClient.newHttpClient()){call(client,endpoint,"stop",List.of());}catch(Exception|AssertionError ignored){}
            if(!core.waitFor(15,TimeUnit.SECONDS)){core.destroyForcibly();core.waitFor(5,TimeUnit.SECONDS);}
        }
    }
    private String descriptor(String expression,List<String> keys,Map<String,byte[]> hashes,boolean taproot){String result=expression;for(int index=0;index<3;index++)result=result.replace("{"+(char)('A'+index)+"}",keys.get(index));for(var hash:hashes.entrySet())result=result.replace("{"+hash.getKey()+"}",HEX.formatHex(hash.getValue()));return taproot?"tr(50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0,"+result.replace("multi(","multi_a(")+")":"wsh("+result+")";}
    private void compareOfflineAmounts(HttpClient client,URI endpoint)throws Exception{
        byte[] script=new byte[]{0x51};int comparisons=0;
        for(boolean taproot:List.of(false,true))for(long amount:List.of(Long.MIN_VALUE,-2L,0L,2100000000000001L,Long.MAX_VALUE))for(long vout:List.of(0L,0xffff_ffffL)){
            byte[] program,control=null;
            if(taproot){
                byte[] internal=HEX.parseHex("50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0");
                byte[] leaf=TaggedHash.hash("TapLeaf",new byte[]{(byte)0xc0},CompactSize.encode(script.length),script);
                var tweak=new java.math.BigInteger(1,TaggedHash.hash("TapTweak",internal,leaf));
                var point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(internal).add(ru.bitcoin.node.crypto.secp256k1.Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
                program=new byte[34];program[0]=0x51;program[1]=32;System.arraycopy(point.getEncoded(true),1,program,2,32);
                control=new byte[33];control[0]=(byte)(0xc0|(point.getAffineYCoord().toBigInteger().testBit(0)?1:0));System.arraycopy(internal,0,control,1,32);
            }else{program=new byte[34];program[1]=32;System.arraycopy(Sha256.hash(script),0,program,2,32);}
            var tx=new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(vout)),new byte[0],new UInt32(100))),List.of(new TxOut(1,new byte[]{0x51})),new UInt32(0));
            var witnessItems=new ArrayList<byte[]>();witnessItems.add(script);if(taproot)witnessItems.add(control);Witness witness=new Witness(witnessItems);
            var document=PsbtDocument.parse(PsbtCreator.create(tx,0));
            var coin=new java.io.ByteArrayOutputStream();coin.writeBytes(LittleEndian.int64(amount));coin.writeBytes(CompactSize.encode(program.length));coin.writeBytes(program);
            var serializedWitness=new java.io.ByteArrayOutputStream();serializedWitness.writeBytes(CompactSize.encode(witnessItems.size()));for(byte[] item:witnessItems){serializedWitness.writeBytes(CompactSize.encode(item.length));serializedWitness.writeBytes(item);}
            document.inputs.getFirst().put("01",new PsbtDocument.Field(1,new byte[]{1},coin.toByteArray(),null));document.inputs.getFirst().put("08",new PsbtDocument.Field(8,new byte[]{8},serializedWitness.toByteArray(),null));
            var expected=(Map<?,?>)call(client,endpoint,"finalizepsbt",List.of(raw(document)));assertEquals(true,expected.get("complete"),"Core offline amount="+amount+" taproot="+taproot+" vout="+vout);
            var spending=new Transaction(2,List.of(new TxIn(tx.inputs().getFirst().previousOutput(),new byte[0],new UInt32(100),witness)),tx.outputs(),tx.lockTime());
            compareAnalysis(client,endpoint,raw(document),"offline amount="+amount+" taproot="+taproot+" vout="+vout);
            ru.bitcoin.node.consensus.transaction.InputScriptValidator.validateKnownInput(spending,0,List.of(new TxOut(amount,program)),StandardScriptVerifyFlags.STANDARD);comparisons++;
        }
        System.out.println("CORE32_PSBT_OFFLINE_AMOUNTS comparisons="+comparisons+" discrepancies=0");
    }
    private void compareStandardFinalizers(HttpClient client,URI endpoint,List<String> privateKeys,List<String> publicKeys)throws Exception{
        int comparisons=0;
        var expressions=new ArrayList<>(List.of("pk({A})","pkh({A})","sh(pkh({A}))","wpkh({A})","sh(wpkh({A}))","multi(2,{A},{B},{C})","sh(multi(2,{A},{B},{C}))","wsh(multi(2,{A},{B},{C}))","sh(wsh(multi(2,{A},{B},{C})))","tr({A})"));
        String twenty=java.util.stream.IntStream.range(0,20).mapToObj(i->"{"+(char)('A'+i%3)+"}").collect(java.util.stream.Collectors.joining(","));
        expressions.add("wsh(multi(17,"+twenty+"))");expressions.add("wsh(multi(20,"+twenty+"))");expressions.add("sh(wsh(multi(17,"+twenty+")))");
        for(String expression:expressions){
            String publicExpression=expression.replace("{A}",publicKeys.get(0)).replace("{B}",publicKeys.get(1)).replace("{C}",publicKeys.get(2));byte[] script;
            if(expression.equals("pk({A})"))script=HEX.parseHex("21"+publicKeys.getFirst()+"ac");
            else if(expression.startsWith("multi("))script=HEX.parseHex("5221"+publicKeys.get(0)+"21"+publicKeys.get(1)+"21"+publicKeys.get(2)+"53ae");
            else{var addresses=(List<?>)call(client,endpoint,"deriveaddresses",List.of(DescriptorChecksum.add(publicExpression)));script=ru.bitcoin.node.protocol.address.BitcoinAddressDecoder.toScriptPubKey(addresses.getFirst().toString(),ru.bitcoin.node.protocol.network.NetworkParametersRegistry.regtest());}
            var previous=new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0xffff_ffffL)),new byte[]{1,1},new UInt32(0xffff_ffffL))),List.of(new TxOut(100000,script)),new UInt32(0));
            var tx=new Transaction(2,List.of(new TxIn(new OutPoint(previous.txId(),new UInt32(0)),new byte[0],new UInt32(100))),List.of(new TxOut(90000,new byte[]{0x51})),new UInt32(1000));
            for(int mask=0;mask<8;mask++)for(int version:List.of(0,2))for(String sighash:List.of("ALL","NONE","SINGLE","ALL|ANYONECANPAY","NONE|ANYONECANPAY","SINGLE|ANYONECANPAY"))for(boolean fullCoin:List.of(false,true)){
                String descriptor=expression;
                for(int key=0;key<3;key++)descriptor=descriptor.replace("{"+(char)('A'+key)+"}",((mask&(1<<key))!=0?privateKeys:publicKeys).get(key));
                var document=PsbtDocument.parse(PsbtCreator.create(tx,version));
                if(fullCoin){byte[] encoded=ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(previous);document.inputs.getFirst().put("00",new PsbtDocument.Field(0,new byte[]{0},encoded,previous));}
                else putCoin(document,0,new TxOut(100000,script));
                var prepared=(Map<?,?>)call(client,endpoint,"descriptorprocesspsbt",List.of(raw(document),List.of(DescriptorChecksum.add(descriptor)),sighash,true,false));
                String encoded=prepared.get("psbt").toString();var expected=(Map<?,?>)call(client,endpoint,"finalizepsbt",List.of(encoded,false));
                var local=PsbtDocument.parse(encoded);boolean complete=new PsbtFinalizer(local).finalizeInputs();String label=expression+" mask="+mask+" version="+version+" sighash="+sighash+" fullCoin="+fullCoin;
                compareAnalysis(client,endpoint,encoded,label);
                assertEquals(expected.get("complete"),complete,label);assertEquals(expected.get("psbt"),PsbtCodec.encode(local),label);
                assertEquals(call(client,endpoint,"finalizepsbt",List.of(encoded,true)),PsbtFinalizer.finish(encoded,true),label+" extracted");comparisons++;
                if((mask==0||mask==7)&&version==0&&sighash.equals("ALL"))for(int mutation=0;mutation<6;mutation++){
                    var changed=PsbtDocument.parse(encoded);var fields=changed.inputs.getFirst();
                    switch(mutation){
                        case 0->fields.put("07",new PsbtDocument.Field(7,new byte[]{7},new byte[]{0x51},null));
                        case 1->fields.put("08",new PsbtDocument.Field(8,new byte[]{8},new byte[]{1,0},null));
                        case 2->fields.put("08",new PsbtDocument.Field(8,new byte[]{8},new byte[]{0},null));
                        case 3->fields.put("03",new PsbtDocument.Field(3,new byte[]{3},new byte[]{3,0,0,0},null));
                        case 4->fields.put("13",new PsbtDocument.Field(0x13,new byte[]{0x13},new byte[64],null));
                        case 5->fields.put("07",new PsbtDocument.Field(7,new byte[]{7},new byte[0],null));
                    }
                    String variant=raw(changed);var nativeVariant=(Map<?,?>)call(client,endpoint,"finalizepsbt",List.of(variant,false));
                    compareAnalysis(client,endpoint,variant,label+" mutation="+mutation);
                    assertEquals(nativeVariant,PsbtFinalizer.finish(variant,false),label+" mutation="+mutation);comparisons++;
                }

            }
        }
        System.out.println("CORE32_PSBT_STANDARD_FINALIZER comparisons="+comparisons+" discrepancies=0");
    }
    private void compareMiniscriptLimits(HttpClient client,URI endpoint,List<String> keys)throws Exception{
        int comparisons=0;
        for(boolean taproot:List.of(false,true)){
            String a=taproot?keys.get(0).substring(2):keys.get(0),b=taproot?keys.get(1).substring(2):keys.get(1);
            var cases=new ArrayList<>(List.of("pk("+a+")","0","1","after(1)","older(1)","after(0)","older(2147483648)",
                "or_i(pk("+a+"),pk("+a+"))","or_i(pk("+a+"),after(10))","and_v(v:after(10),pk("+a+"))",
                "and_v(v:after(10),and_v(v:after(500000000),pk("+a+")))","and_v(v:older(10),and_v(v:older(4194304),pk("+a+")))",
                "or_i(and_v(v:after(10),pk("+a+")),and_v(v:after(500000000),pk("+b+")))",
                "and_v(v:older(10),and_v(v:after(500000000),pk("+a+")))"));
            for(int wrappers:List.of(199,200,201,202,2000))cases.add("n".repeat(wrappers)+":pk("+a+")");
            for(String expression:cases){
                var parsed=MiniscriptText.parse(expression,taproot,key->key);
                boolean local=false;if(parsed!=null){var limits=MiniscriptLimits.analyze(parsed,Comparator.naturalOrder());local=limits.sane()&&limits.satisfiable();}
                String descriptor=taproot?"tr(50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0,"+expression+")":"wsh("+expression+")";
                boolean nativeAccepted=true;try{call(client,endpoint,"getdescriptorinfo",List.of(DescriptorChecksum.add(descriptor)));}catch(AssertionError invalid){nativeAccepted=false;}
                assertEquals(nativeAccepted,local,"Native miniscript limits taproot="+taproot+" "+expression);comparisons++;
            }
        }
        System.out.println("CORE32_MINISCRIPT_LIMITS comparisons="+comparisons+" discrepancies=0");
    }
    private void compareAnalysisEdges(HttpClient client,URI endpoint)throws Exception{
        var first=new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(1)),new byte[0],new UInt32(0));
        byte[] otherHash=new byte[32];Arrays.fill(otherHash,(byte)1);var second=new TxIn(new OutPoint(new Hash256(otherHash),new UInt32(1)),new byte[0],new UInt32(0));
        var amounts=List.of(Long.MIN_VALUE,-2L,-1L,0L,1L,2100000000000000L,2100000000000001L,Long.MAX_VALUE);
        for(int version:List.of(0,2))for(long inputAmount:amounts)for(long outputAmount:amounts)for(boolean fullCoin:List.of(false,true)){
            var previous=new Transaction(2,List.of(second),List.of(new TxOut(inputAmount,new byte[]{0x51})),new UInt32(0));
            var source=fullCoin?new TxIn(new OutPoint(previous.txId(),new UInt32(0)),new byte[0],new UInt32(0)):first;
            var transaction=new Transaction(2,List.of(source),List.of(new TxOut(outputAmount,new byte[]{0x51})),new UInt32(0));
            var document=PsbtDocument.parse(PsbtCreator.create(transaction,version));
            if(fullCoin){byte[] bytes=ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(previous);document.inputs.getFirst().put("00",new PsbtDocument.Field(0,new byte[]{0},bytes,previous));}
            else putCoin(document,0,new TxOut(inputAmount,new byte[]{0x51}));
            compareAnalysis(client,endpoint,raw(document),"money input="+inputAmount+" output="+outputAmount+" fullCoin="+fullCoin+" psbt="+version);
        }
        for(int version:List.of(0,2))for(int sigops:List.of(0,1,20,100,10001))for(byte[] script:List.of(new byte[]{0x51},new byte[]{0},new byte[]{0x6a},new byte[10001],new byte[]{0x51,2,0x4e,0x73}))for(int known:List.of(0,1,2)){
            byte[] outputScript=new byte[sigops];Arrays.fill(outputScript,(byte)0xac);
            var transaction=new Transaction(2,List.of(first,second),List.of(new TxOut(1,outputScript)),new UInt32(0));
            var document=PsbtDocument.parse(PsbtCreator.create(transaction,version));
            if(known>0)putCoin(document,0,new TxOut(2100000000000000L,script));
            if(known>1)putCoin(document,1,new TxOut(1,new byte[]{0x51}));
            compareAnalysis(client,endpoint,raw(document),"sigops="+sigops+" scriptSize="+script.length+" known="+known+" psbt="+version);
        }
    }
    private void compareAnalysis(HttpClient client,URI endpoint,String encoded,String label)throws Exception{
        Object expected=call(client,endpoint,"analyzepsbt",List.of(encoded));
        Object actual=json.readValue(json.writeValueAsString(PsbtAnalyzer.analyze(encoded)),Map.class);
        assertEquals(expected,actual,label+" analysis");analysisComparisons++;
    }
    private void compareMissingCoins(HttpClient client,URI endpoint)throws Exception{
        int comparisons=0;String nums="50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0";
        byte[] secret=new byte[33];secret[31]=1;secret[32]=1;String wif=BitcoinAddressEncoder.legacy(239,secret);
        for(boolean taproot:List.of(false,true))for(boolean signature:List.of(false,true)){
            byte[] program;Witness witness;
            var firstInput=new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0)),new byte[0],new UInt32(100));
            var output=new TxOut(90000,new byte[]{0x51});
            if(signature){
                String descriptor=DescriptorChecksum.add(taproot?"tr("+nums+",pk("+wif+"))":"wsh(pk("+wif+"))");
                var addresses=(List<?>)call(client,endpoint,"deriveaddresses",List.of(descriptor));var address=(Map<?,?>)call(client,endpoint,"validateaddress",List.of(addresses.getFirst()));program=HEX.parseHex(address.get("scriptPubKey").toString());
                var unsigned=new Transaction(2,List.of(firstInput),List.of(output),new UInt32(1000));var document=PsbtDocument.parse(PsbtCreator.create(unsigned,0));putCoin(document,0,new TxOut(100000,program));
                var signed=(Map<?,?>)call(client,endpoint,"descriptorprocesspsbt",List.of(raw(document),List.of(descriptor),"ALL|ANYONECANPAY",true,true));assertEquals(true,signed.get("complete"));
                witness=TransactionParser.parse(HEX.parseHex(signed.get("hex").toString())).inputs().getFirst().witness();
            }else{
                byte[] script=new byte[]{0x51};program=new byte[34];program[1]=32;
                if(taproot){
                    program[0]=0x51;byte[] internal=HEX.parseHex(nums),leaf=TaggedHash.hash("TapLeaf",new byte[]{(byte)0xc0},CompactSize.encode(1),script);
                    var tweak=new java.math.BigInteger(1,TaggedHash.hash("TapTweak",internal,leaf));var point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(internal).add(ru.bitcoin.node.crypto.secp256k1.Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
                    System.arraycopy(point.getEncoded(true),1,program,2,32);byte[] control=new byte[33];control[0]=(byte)(0xc0|(point.getAffineYCoord().toBigInteger().testBit(0)?1:0));System.arraycopy(internal,0,control,1,32);witness=new Witness(List.of(script,control));
                }else{System.arraycopy(Sha256.hash(script),0,program,2,32);witness=new Witness(List.of(script));}
            }
            for(boolean otherKnown:List.of(false,true)){
                byte[] hash=new byte[32];Arrays.fill(hash,(byte)1);var secondInput=new TxIn(new OutPoint(new Hash256(hash),new UInt32(0)),new byte[0],new UInt32(100));
                var transaction=new Transaction(2,List.of(firstInput,secondInput),List.of(output),new UInt32(1000));var document=PsbtDocument.parse(PsbtCreator.create(transaction,0));var coin=new TxOut(100000,program);putCoin(document,0,coin);
                var encoded=new java.io.ByteArrayOutputStream();encoded.writeBytes(CompactSize.encode(witness.size()));for(byte[] item:witness.items()){encoded.writeBytes(CompactSize.encode(item.length));encoded.writeBytes(item);}
                document.inputs.getFirst().put("08",new PsbtDocument.Field(8,new byte[]{8},encoded.toByteArray(),null));TxOut other=otherKnown?new TxOut(1,new byte[]{0x51}):null;if(otherKnown)putCoin(document,1,other);
                var analyzed=(Map<?,?>)call(client,endpoint,"analyzepsbt",List.of(raw(document)));boolean expected=Boolean.TRUE.equals(((Map<?,?>)((List<?>)analyzed.get("inputs")).getFirst()).get("is_final"));
                boolean actual=true;ScriptExecutionException failure=null;try{ru.bitcoin.node.consensus.transaction.InputScriptValidator.validateKnownInputData(transaction,0,Arrays.asList(coin,other),new byte[0],witness,StandardScriptVerifyFlags.STANDARD);}
                catch(ScriptExecutionException invalid){actual=false;failure=invalid;}
                compareAnalysis(client,endpoint,raw(document),"missing coins taproot="+taproot+" signature="+signature+" other="+otherKnown);
                assertEquals(!taproot||!signature||otherKnown,expected,"Native missing data contract");assertEquals(expected,actual,"taproot="+taproot+" signature="+signature+" other="+otherKnown);
                assertEquals(expected,new PsbtScriptVerifier(PsbtDocument.parse(raw(document))).signedAndVerified(PsbtDocument.parse(raw(document)).inputs.getFirst(),0));
                if(!actual){assertNotNull(failure);assertEquals(ScriptError.SCHNORR_SIG_HASHTYPE,failure.code());}comparisons++;
            }
        }
        System.out.println("CORE32_PSBT_MISSING_COINS comparisons="+comparisons+" discrepancies=0");
    }
    private static void putCoin(PsbtDocument document,int index,TxOut coin){var value=new java.io.ByteArrayOutputStream();value.writeBytes(LittleEndian.int64(coin.value()));value.writeBytes(CompactSize.encode(coin.scriptPubKeyLength()));value.writeBytes(coin.scriptPubKey());document.inputs.get(index).put("01",new PsbtDocument.Field(1,new byte[]{1},value.toByteArray(),null));}
    private Object call(HttpClient client,URI endpoint,String method,Object params)throws Exception{
        String auth="Basic "+Base64.getEncoder().encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var request=HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(20)).header("Authorization",auth).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id",1,"method",method,"params",params)))).build();
        var response=json.readValue(client.send(request,HttpResponse.BodyHandlers.ofString()).body(),Map.class);if(response.get("error")!=null)throw new AssertionError(method+" "+response.get("error"));return response.get("result");
    }
    private static String raw(PsbtDocument document){var output=new java.io.ByteArrayOutputStream();output.writeBytes(new byte[]{0x70,0x73,0x62,0x74,(byte)0xff});var maps=new ArrayList<PsbtDocument.Fields>();maps.add(document.globals);maps.addAll(document.inputs);maps.addAll(document.outputs);
        for(var fields:maps){for(var field:fields.values()){output.writeBytes(CompactSize.encode(field.key().length));output.writeBytes(field.key());output.writeBytes(CompactSize.encode(field.value().length));output.writeBytes(field.value());}output.write(0);}return Base64.getEncoder().encodeToString(output.toByteArray());}
}

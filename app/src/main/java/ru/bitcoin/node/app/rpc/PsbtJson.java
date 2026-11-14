package ru.bitcoin.node.app.rpc;

// PSBT RPC presentation: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;
import java.math.BigDecimal;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;

/** Full decodepsbt presentation; document parsing and display share no admission state. */
final class PsbtJson {
    private PsbtJson() { }
    static Map<String,Object> decode(PsbtDocument document,NetworkParameters network){
        var result=new LinkedHashMap<String,Object>();
        if(document.version==0)result.put("tx",transaction(document.unsigned,network));
        var xpubs=new TreeMap<Origin,TreeMap<String,Field>>();
        for(Field field:PsbtCodec.xpubs(document.globals)){
            Origin origin=new Origin(field.value());String identity=hex(field.key(),46,79)+hex(field.key(),14,46);
            xpubs.computeIfAbsent(origin,unused->new TreeMap<>()).putIfAbsent(identity,field);
        }
        var globalKeys=new ArrayList<Map<String,Object>>();
        xpubs.forEach((origin,keys)->keys.values().forEach(field->{var keypath=origin.json();
            keypath.put("xpub",BitcoinAddressEncoder.legacy(field.key()[1]&255,Arrays.copyOfRange(field.key(),2,field.key().length)));globalKeys.add(keypath);}));
        result.put("global_xpubs",globalKeys);
        if(document.version>=2){result.put("tx_version",document.globals.number(2,0));
            if(document.globals.has(3))result.put("fallback_locktime",document.globals.number(3,0));
            result.put("input_count",document.inputs.size());result.put("output_count",document.outputs.size());
            if(document.globals.has(6)){long flags=document.globals.number(6,0);result.put("inputs_modifiable",(flags&1)!=0);result.put("outputs_modifiable",(flags&2)!=0);result.put("has_sighash_single",(flags&4)!=0);}}
        result.put("psbt_version",document.version);result.put("proprietary",proprietary(document.globals));result.put("unknown",unknown(document.globals,"global"));
        var inputs=new ArrayList<Map<String,Object>>();long totalInput=0,totalOutput=0;boolean all=true;
        for(int index=0;index<document.inputs.size();index++){
            Fields fields=document.inputs.get(index);var input=new LinkedHashMap<String,Object>();TxOut coin=null;
            if(fields.has(1)){TxOut witness=(TxOut)fields.field(1).decoded();if(witness.value()!=-1){coin=witness;input.put("witness_utxo",Map.of("amount",amount(witness.value()),"scriptPubKey",ScriptJson.output(witness.scriptPubKey(),network)));}}
            if(fields.has(0)){Transaction previous=(Transaction)fields.field(0).decoded();long vout=document.version==0?document.unsigned.inputs().get(index).previousOutput().outputIndex().value():fields.number(0x0f,0);
                coin=previous.outputs().get((int)vout);input.put("non_witness_utxo",transaction(previous,network));}
            if(coin==null||!money(coin.value())||!money(totalInput+coin.value()))all=false;else totalInput+=coin.value();
            var signatures=new TreeMap<String,String>();for(Field field:fields(fields,2))signatures.put(hex(field.key(),1,field.key().length),hex(field.value()));
            if(!signatures.isEmpty())input.put("partial_signatures",signatures);
            if(fields.has(3))input.put("sighash",sighash((int)fields.number(3,0)));
            script(input,"redeem_script",fields.field(4),network);script(input,"witness_script",fields.field(5),network);
            derivations(input,fields,6,false);
            if(nonempty(fields.field(7)))input.put("final_scriptSig",Map.of("asm",ScriptJson.assembly(fields.field(7).value(),true),"hex",hex(fields.field(7).value())));
            if(fields.has(8)){Witness witness=(Witness)fields.field(8).decoded();if(!witness.isEmpty())input.put("final_scriptwitness",witness.items().stream().map(PsbtJson::hex).toList());}
            for(int type=0x0a;type<=0x0d;type++){var preimages=new TreeMap<String,String>();for(Field field:fields(fields,type))preimages.put(hex(field.key(),1,field.key().length),hex(field.value()));
                if(!preimages.isEmpty())input.put(switch(type){case 0x0a->"ripemd160_preimages";case 0x0b->"sha256_preimages";case 0x0c->"hash160_preimages";default->"hash256_preimages";},preimages);}
            if(document.version>=2){input.put("previous_txid",new Hash256(fields.field(0x0e).value()).toDisplayHex());input.put("previous_vout",fields.number(0x0f,0));
                if(fields.has(0x10))input.put("sequence",fields.number(0x10,0));if(fields.has(0x11))input.put("time_locktime",fields.number(0x11,0));if(fields.has(0x12))input.put("height_locktime",fields.number(0x12,0));}
            if(nonempty(fields.field(0x13)))input.put("taproot_key_path_sig",hex(fields.field(0x13).value()));
            var tapSignatures=new ArrayList<Map<String,Object>>();for(Field field:fields(fields,0x14))tapSignatures.add(Map.of("pubkey",hex(field.key(),1,33),"leaf_hash",hex(field.key(),33,65),"sig",hex(field.value())));
            if(!tapSignatures.isEmpty())input.put("taproot_script_path_sigs",tapSignatures);
            var leaves=new TreeMap<String,List<byte[]>>();for(Field field:fields(fields,0x15))leaves.computeIfAbsent(hex(field.value()),unused->new ArrayList<>()).add(Arrays.copyOfRange(field.key(),1,field.key().length));
            // Core groups by (script, leaf_version), not by the control block key.
            var leafKeys=new ArrayList<>(leaves.keySet());leafKeys.sort((a,b)->{int script=a.substring(0,a.length()-2).compareTo(b.substring(0,b.length()-2));return script!=0?script:a.substring(a.length()-2).compareTo(b.substring(b.length()-2));});
            var leafJson=new ArrayList<Map<String,Object>>();for(String leaf:leafKeys){var controls=leaves.get(leaf);controls.sort((a,b)->a.length!=b.length?Integer.compare(a.length,b.length):Arrays.compareUnsigned(a,b));
                leafJson.add(Map.of("script",leaf.substring(0,leaf.length()-2),"leaf_ver",Integer.parseInt(leaf.substring(leaf.length()-2),16),"control_blocks",controls.stream().map(PsbtJson::hex).toList()));}
            if(!leafJson.isEmpty())input.put("taproot_scripts",leafJson);
            derivations(input,fields,0x16,true);nonzero(input,"taproot_internal_key",fields.field(0x17));nonzero(input,"taproot_merkle_root",fields.field(0x18));
            participants(input,fields,0x1a);musig(input,fields,0x1b,"musig2_pubnonces","pubnonce");musig(input,fields,0x1c,"musig2_partial_sigs","partial_sig");
            extras(input,fields,"input");inputs.add(input);
        }
        result.put("inputs",inputs);var outputs=new ArrayList<Map<String,Object>>();
        for(int index=0;index<document.outputs.size();index++){
            Fields fields=document.outputs.get(index);var output=new LinkedHashMap<String,Object>();
            script(output,"redeem_script",fields.field(0),network);script(output,"witness_script",fields.field(1),network);derivations(output,fields,2,false);
            long value=document.version==0?document.unsigned.outputs().get(index).value():fields.number(3,0);
            if(document.version>=2){output.put("amount",amount(value));output.put("script",ScriptJson.output(fields.field(4).value(),network));}
            if(!money(value)||!money(totalOutput+value))all=false;else totalOutput+=value;
            nonzero(output,"taproot_internal_key",fields.field(5));
            if(fields.has(6)){Reader tree=new Reader(fields.field(6).value());var leaves=new ArrayList<Map<String,Object>>();while(tree.remaining()>0)leaves.add(Map.of("depth",tree.byteValue(),"leaf_ver",tree.byteValue(),"script",hex(tree.vector())));output.put("taproot_tree",leaves);}
            derivations(output,fields,7,true);participants(output,fields,8);extras(output,fields,"output");outputs.add(output);
        }
        result.put("outputs",outputs);if(all)result.put("fee",amount(totalInput-totalOutput));return result;
    }

    private static Map<String,Object> transaction(Transaction transaction,NetworkParameters network){var json=NodeRpcServer.transactionJson(transaction,network);json.remove("hex");return json;}
    private static void extras(Map<String,Object> result,Fields fields,String scope){var proprietary=proprietary(fields);if(!proprietary.isEmpty())result.put("proprietary",proprietary);var unknown=unknown(fields,scope);if(!unknown.isEmpty())result.put("unknown",unknown);}
    private static Map<String,String> unknown(Fields fields,String scope){var result=new TreeMap<String,String>();for(Field field:fields.values()){
        int type=field.type();boolean known=scope.equals("global")?type<=6||type==0xfb||type==0xfc:scope.equals("output")?type<=8||type==0xfc:type<=8||type>=0x0a&&type<=0x18||type>=0x1a&&type<=0x1c||type==0xfc;
        if(!known)result.put(hex(field.key()),hex(field.value()));}return result;}
    private static List<Map<String,Object>> proprietary(Fields fields){var result=new ArrayList<Map<String,Object>>();for(Field field:fields(fields,0xfc)){
        Reader key=new Reader(field.key());key.compact();byte[] identifier=key.vector();long subtype=key.compact();result.add(Map.of("identifier",hex(identifier),"subtype",subtype,"key",hex(field.key()),"value",hex(field.value())));}return result;}
    private static void script(Map<String,Object> result,String name,Field field,NetworkParameters network){if(!nonempty(field))return;var script=ScriptJson.output(field.value(),network);script.remove("address");script.remove("desc");result.put(name,script);}
    private static void nonzero(Map<String,Object> result,String name,Field field){if(field!=null&&nonzero(field.value()))result.put(name,hex(field.value()));}
    private static boolean nonzero(byte[] value){for(byte b:value)if(b!=0)return true;return false;}
    private static boolean nonempty(Field field){return field!=null&&field.value().length>0;}
    private static boolean money(long value){return value>=0&&value<=2_100_000_000_000_000L;}
    private static BigDecimal amount(long value){return BigDecimal.valueOf(value,8);}
    private static String sighash(int value){return switch(value){case 1->"ALL";case 2->"NONE";case 3->"SINGLE";case 0x81->"ALL|ANYONECANPAY";case 0x82->"NONE|ANYONECANPAY";case 0x83->"SINGLE|ANYONECANPAY";default->"";};}
    private static List<Field> fields(Fields fields,int type){return fields.values().stream().filter(field->field.type()==type).sorted(Comparator.comparing(field->hex(field.key()))).toList();}
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    private static String hex(byte[] value,int from,int to){return HexFormat.of().formatHex(value,from,to);}

    private static void derivations(Map<String,Object> result,Fields fields,int type,boolean taproot){var paths=new ArrayList<Map<String,Object>>();
        for(Field field:fields(fields,type)){Reader reader=new Reader(field.value());var leaves=new TreeSet<String>();if(taproot){long count=reader.compact();for(long i=0;i<count;i++)leaves.add(hex(reader.bytes(32)));}
            Origin origin=new Origin(reader.bytes(reader.remaining()));var path=origin.json();path.put("pubkey",hex(field.key(),1,field.key().length));if(taproot)path.put("leaf_hashes",new ArrayList<>(leaves));paths.add(path);}
        if(!paths.isEmpty())result.put(taproot?"taproot_bip32_derivs":"bip32_derivs",paths);
    }
    private static void participants(Map<String,Object> result,Fields fields,int type){var participants=new ArrayList<Map<String,Object>>();for(Field field:fields(fields,type)){Reader values=new Reader(field.value());var keys=new ArrayList<String>();while(values.remaining()>0)keys.add(hex(values.bytes(33)));participants.add(Map.of("aggregate_pubkey",hex(field.key(),1,34),"participant_pubkeys",keys));}
        if(!participants.isEmpty())result.put("musig2_participant_pubkeys",participants);}
    private static void musig(Map<String,Object> result,Fields fields,int type,String name,String valueName){var ordered=new TreeMap<String,Field>();for(Field field:fields.values())if(field.type()==type){
        String aggregate=hex(field.key(),34,67),participant=hex(field.key(),1,34),leaf=field.key().length==99?hex(field.key(),67,99):"00".repeat(32);ordered.putIfAbsent(aggregate+leaf+participant,field);}
        var values=new ArrayList<Map<String,Object>>();ordered.forEach((identity,field)->{var info=new LinkedHashMap<String,Object>();info.put("participant_pubkey",identity.substring(130));info.put("aggregate_pubkey",identity.substring(0,66));
            String leaf=identity.substring(66,130);if(!leaf.equals("00".repeat(32)))info.put("leaf_hash",leaf);info.put(valueName,hex(field.value()));values.add(info);});if(!values.isEmpty())result.put(name,values);
    }
    private static final class Origin implements Comparable<Origin>{
        final byte[] fingerprint;final List<Long> path=new ArrayList<>();
        Origin(byte[] bytes){Reader reader=new Reader(bytes);fingerprint=reader.bytes(4);while(reader.remaining()>0)path.add(reader.number(4));}
        Map<String,Object> json(){var result=new LinkedHashMap<String,Object>();result.put("master_fingerprint",hex(fingerprint));var text=new StringBuilder("m");for(long index:path)text.append('/').append(index&0x7fff_ffffL).append(index>=0x8000_0000L?"h":"");result.put("path",text.toString());return result;}
        @Override public int compareTo(Origin other){int comparison=Arrays.compareUnsigned(fingerprint,other.fingerprint);if(comparison!=0)return comparison;for(int i=0;i<Math.min(path.size(),other.path.size());i++){comparison=Long.compare(path.get(i),other.path.get(i));if(comparison!=0)return comparison;}return Integer.compare(path.size(),other.path.size());}
    }
}

package ru.bitcoin.node.app.rpc;

// PSBT serialization and merging: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;

/** Core's canonical map order, omission rules and BIP174/BIP370 Combiner role. */
final class PsbtCodec {
    private PsbtCodec() { }
    private static final java.security.SecureRandom RANDOM=new java.security.SecureRandom();

    static String join(List<?> encoded){
        if(encoded.size()<=1)throw new RpcException(-8,"At least two PSBTs are required to join PSBTs.");
        var documents=new ArrayList<PsbtDocument>();long version=1,locktime=0xffff_ffffL;
        for(Object value:encoded){
            if(!(value instanceof String text))throw stringType(value);
            PsbtDocument document=PsbtDocument.parse(text);
            if(document.version!=0)throw new RpcException(-8,"joinpsbts only operates on version 0 PSBTs");
            documents.add(document);version=Math.max(version,Integer.toUnsignedLong(document.unsigned.version()));locktime=Math.min(locktime,document.unsigned.lockTime().value());
        }
        record Input(TxIn transaction,Fields fields){}
        record Output(TxOut transaction,Fields fields){}
        var inputs=new ArrayList<Input>();var outputs=new ArrayList<Output>();var outpoints=new HashSet<OutPoint>();
        for(PsbtDocument document:documents){
            for(int index=0;index<document.inputs.size();index++){
                TxIn input=document.unsigned.inputs().get(index);OutPoint point=input.previousOutput();
                if(!outpoints.add(point))throw new RpcException(-8,"Input "+point.transactionId().toDisplayHex()+":"+point.outputIndex().value()+" exists in multiple PSBTs");
                var metadata=new Fields();for(Field field:document.inputs.get(index).values())if(field.type()!=2&&field.type()!=7&&field.type()!=8)metadata.put(hex(field.key()),field);
                inputs.add(new Input(input,metadata));
            }
            for(int index=0;index<document.outputs.size();index++)outputs.add(new Output(document.unsigned.outputs().get(index),document.outputs.get(index)));
        }
        Collections.shuffle(inputs,RANDOM);Collections.shuffle(outputs,RANDOM);
        Transaction transaction=new Transaction((int)version,inputs.stream().map(Input::transaction).toList(),outputs.stream().map(Output::transaction).toList(),new ru.bitcoin.node.common.types.UInt32(locktime));
        PsbtDocument result=PsbtDocument.parse(PsbtCreator.create(transaction,0));
        for(int index=0;index<inputs.size();index++)result.inputs.set(index,inputs.get(index).fields());
        for(int index=0;index<outputs.size();index++)result.outputs.set(index,outputs.get(index).fields());
        var identities=new HashSet<String>();
        for(PsbtDocument document:documents){
            for(Field field:xpubs(document.globals))if(identities.add(xpubIdentity(field)))result.globals.put(hex(field.key()),field);
            for(Field field:document.globals.values())if(field.type()==0xfc||!known(field.type(),"global"))result.globals.putIfAbsent(hex(field.key()),field);
        }
        return encode(result);
    }

    static String combine(List<?> encoded){
        if(encoded.isEmpty())throw new RpcException(-8,"Parameter 'txs' cannot be empty");
        var documents=new ArrayList<PsbtDocument>();for(Object value:encoded){
            if(!(value instanceof String text))throw stringType(value);
            documents.add(PsbtDocument.parse(text));
        }
        PsbtDocument result=documents.getFirst();
        for(int index=1;index<documents.size();index++){
            PsbtDocument other=documents.get(index);var current=result.transaction(true);var incoming=other.transaction(true);
            if(current.isEmpty()||incoming.isEmpty()||result.version!=other.version||!current.get().txId().equals(incoming.get().txId()))
                throw new RpcException(-8,"PSBTs not compatible (different transactions)");
            for(int i=0;i<result.inputs.size();i++)merge(result.inputs.get(i),other.inputs.get(i),"input");
            for(int i=0;i<result.outputs.size();i++)merge(result.outputs.get(i),other.outputs.get(i),"output");
            var identities=new HashSet<String>();for(Field field:fields(result.globals,1))identities.add(xpubIdentity(field));
            for(Field field:xpubs(other.globals))if(identities.add(xpubIdentity(field)))result.globals.put(hex(field.key()),field);
            if(!result.globals.has(3)&&other.globals.has(3))result.globals.put("03",other.globals.field(3));
            if(result.globals.has(6)||other.globals.has(6)){
                long a=result.globals.number(6,0),b=other.globals.number(6,0);byte flags=(byte)((a&b&~4)|((a|b)&4));
                result.globals.put("06",new Field(6,new byte[]{6},new byte[]{flags},new byte[]{flags}));
            }
            for(Field field:other.globals.values())if(field.type()==0xfc||!known(field.type(),"global"))result.globals.putIfAbsent(hex(field.key()),field);
        }
        return encode(result);
    }

    static RpcException stringType(Object value){String type=value==null?"null":value instanceof Number?"number":value instanceof Boolean?"bool":value instanceof List<?>?"array":"object";
        return new RpcException(-3,"JSON value of type "+type+" is not of expected type string");}

    private static void merge(Fields target,Fields source,String scope){
        for(Field incoming:source.values()){
            String key=hex(incoming.key());Field current=target.get(key);
            if(current==null||emptyScalar(current,scope))target.put(key,incoming);
        }
    }
    private static boolean emptyScalar(Field field,String scope){
        int type=field.type();if(scope.equals("input")){
            if(type==1)return ((TxOut)field.decoded()).value()==-1;
            if(type==4||type==5||type==7||type==0x13)return field.value().length==0;
            if(type==8)return ((Witness)field.decoded()).isEmpty();
            if(type==0x17||type==0x18)return !nonzero(field.value());
        }else{
            if(type==0||type==1||type==6)return field.value().length==0;
            if(type==5)return !nonzero(field.value());
        }
        return false;
    }

    static String encode(PsbtDocument document){
        var output=new ByteArrayOutputStream();output.writeBytes(new byte[]{0x70,0x73,0x62,0x74,(byte)0xff});
        if(document.version==0)entry(output,new byte[]{0},TransactionSerializer.serializeLegacy(document.unsigned));
        for(Field field:xpubs(document.globals))entry(output,field);
        if(document.version>=2){for(int type:List.of(2,3,4,5,6))write(output,document.globals.field(type));write(output,document.globals.field(0xfb));}
        group(output,document.globals,0xfc);unknown(output,document.globals,"global");output.write(0);
        for(Fields input:document.inputs){
            if(input.has(0))entry(output,new byte[]{0},TransactionSerializer.serializeLegacy((Transaction)input.field(0).decoded()));
            if(input.has(1)&&!emptyScalar(input.field(1),"input"))entry(output,input.field(1));
            boolean finalized=input.has(7)&&input.field(7).value().length!=0||input.has(8)&&!((Witness)input.field(8).decoded()).isEmpty();
            if(!finalized){
                var signatures=new ArrayList<>(fields(input,2));signatures.sort(Comparator.comparing(field->hex(Hash160.hash(Arrays.copyOfRange(field.key(),1,field.key().length)))));for(Field field:signatures)entry(output,field);
                write(output,input.field(3));nonempty(output,input.field(4));nonempty(output,input.field(5));group(output,input,6);
                for(int type=0x0a;type<=0x0d;type++)group(output,input,type);
                nonempty(output,input.field(0x13));group(output,input,0x14);
                var leaves=new ArrayList<>(fields(input,0x15));leaves.sort((a,b)->{int cmp=Arrays.compareUnsigned(a.value(),0,a.value().length-1,b.value(),0,b.value().length-1);
                    if(cmp==0)cmp=Integer.compare(a.value()[a.value().length-1]&255,b.value()[b.value().length-1]&255);
                    if(cmp==0)cmp=Integer.compare(a.key().length,b.key().length);return cmp!=0?cmp:Arrays.compareUnsigned(a.key(),b.key());});for(Field field:leaves)entry(output,field);
                tapOrigins(output,input,0x16);nonzero(output,input.field(0x17));nonzero(output,input.field(0x18));group(output,input,0x1a);musig(output,input,0x1b);musig(output,input,0x1c);
            }
            nonempty(output,input.field(7));if(input.has(8)&&!((Witness)input.field(8).decoded()).isEmpty())entry(output,input.field(8));
            if(document.version>=2)for(int type=0x0e;type<=0x12;type++)write(output,input.field(type));
            group(output,input,0xfc);unknown(output,input,"input");output.write(0);
        }
        for(Fields fields:document.outputs){
            nonempty(output,fields.field(0));nonempty(output,fields.field(1));group(output,fields,2);
            if(document.version>=2){write(output,fields.field(3));write(output,fields.field(4));}
            group(output,fields,0xfc);nonzero(output,fields.field(5));nonempty(output,fields.field(6));tapOrigins(output,fields,7);group(output,fields,8);unknown(output,fields,"output");output.write(0);
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static void tapOrigins(ByteArrayOutputStream output,Fields fields,int type){
        for(Field field:fields(fields,type)){
            Reader value=new Reader(field.value());long count=value.compact();var leaves=new TreeSet<String>();for(long i=0;i<count;i++)leaves.add(hex(value.bytes(32)));
            var encoded=new ByteArrayOutputStream();encoded.writeBytes(CompactSize.encode(leaves.size()));for(String leaf:leaves)encoded.writeBytes(HexFormat.of().parseHex(leaf));encoded.writeBytes(value.bytes(value.remaining()));entry(output,field.key(),encoded.toByteArray());
        }
    }
    private static void musig(ByteArrayOutputStream output,Fields fields,int type){
        var values=new TreeMap<String,Field>();for(Field field:fields.values())if(field.type()==type){String identity=hex(field.key(),34,67)+(field.key().length==99?hex(field.key(),67,99):"00".repeat(32))+hex(field.key(),1,34);values.putIfAbsent(identity,field);}
        values.forEach((identity,field)->{byte[] key=identity.substring(66,130).equals("00".repeat(32))?Arrays.copyOf(field.key(),67):field.key();entry(output,key,field.value());});
    }
    private static int compareOrigins(byte[] a,byte[] b){int comparison=Arrays.compareUnsigned(a,0,4,b,0,4);if(comparison!=0)return comparison;
        Reader first=new Reader(a),second=new Reader(b);first.bytes(4);second.bytes(4);while(first.remaining()>0&&second.remaining()>0){comparison=Long.compare(first.number(4),second.number(4));if(comparison!=0)return comparison;}return Integer.compare(first.remaining(),second.remaining());}
    static List<Field> xpubs(Fields fields){
        // Preserve first-inserted aliases when Core's CExtPubKey comparator
        // treats different serialized metadata as the same pubkey/chaincode.
        var ordered=new ArrayList<Field>();for(Field field:fields.values())if(field.type()==1)ordered.add(field);
        ordered.sort((a,b)->{int origin=compareOrigins(a.value(),b.value());return origin!=0?origin:xpubIdentity(a).compareTo(xpubIdentity(b));});
        var seen=new HashSet<String>();var result=new ArrayList<Field>();for(Field field:ordered)if(seen.add(hex(field.value())+":"+xpubIdentity(field)))result.add(field);return result;
    }
    private static String xpubIdentity(Field field){return hex(field.key(),46,79)+hex(field.key(),14,46);}
    private static List<Field> fields(Fields fields,int type){return fields.values().stream().filter(field->field.type()==type).sorted(Comparator.comparing(field->hex(field.key()))).toList();}
    private static void group(ByteArrayOutputStream output,Fields fields,int type){for(Field field:fields(fields,type))entry(output,field);}
    private static void unknown(ByteArrayOutputStream output,Fields fields,String scope){fields.values().stream().filter(field->!known(field.type(),scope)).sorted(Comparator.comparing(field->hex(field.key()))).forEach(field->entry(output,field));}
    private static boolean known(int type,String scope){return scope.equals("global")?type<=6||type==0xfb||type==0xfc:scope.equals("output")?type<=8||type==0xfc:type<=8||type>=0x0a&&type<=0x18||type>=0x1a&&type<=0x1c||type==0xfc;}
    private static boolean nonzero(byte[] bytes){for(byte value:bytes)if(value!=0)return true;return false;}
    private static void nonzero(ByteArrayOutputStream output,Field field){if(field!=null&&nonzero(field.value()))entry(output,field);}
    private static void nonempty(ByteArrayOutputStream output,Field field){if(field!=null&&field.value().length!=0)entry(output,field);}
    private static void write(ByteArrayOutputStream output,Field field){if(field!=null)entry(output,field);}
    private static void entry(ByteArrayOutputStream output,Field field){entry(output,field.key(),field.value());}
    private static void entry(ByteArrayOutputStream output,byte[] key,byte[] value){output.writeBytes(CompactSize.encode(key.length));output.writeBytes(key);output.writeBytes(CompactSize.encode(value.length));output.writeBytes(value);}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static String hex(byte[] bytes,int from,int to){return HexFormat.of().formatHex(bytes,from,to);}
}

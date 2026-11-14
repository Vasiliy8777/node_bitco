package ru.bitcoin.node.app.rpc;

// PSBT field semantics: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.SignatureEncoding;
import java.util.*;

/** Strict BIP174/BIP370 document reader shared by the validated PSBT roles. */
final class PsbtDocument {
    record Field(int type, byte[] key, byte[] value, Object decoded) { }
    static final class Fields extends LinkedHashMap<String, Field> {
        Field field(int type) { return get(String.format("%02x", type)); }
        boolean has(int type) { return field(type) != null; }
        long number(int type, long fallback) { return has(type) ? new Reader(field(type).value()).number(field(type).value().length) : fallback; }
    }
    final Fields globals;
    final List<Fields> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    final int version;
    final Transaction unsigned;

    static PsbtDocument parse(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
            if (base64.length() % 4 != 0 || !Base64.getEncoder().encodeToString(bytes).equals(base64)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new RpcException(-22, "TX decode failed invalid base64"); }
        try { return new PsbtDocument(new Reader(bytes)); }
        catch (FormatError invalid) { throw new RpcException(-22, "TX decode failed " + invalid.getMessage()); }
    }

    private PsbtDocument(Reader reader) {
        if (!Arrays.equals(reader.bytes(5), new byte[]{0x70,0x73,0x62,0x74,(byte)0xff})) fail("Invalid PSBT magic bytes");
        globals = map(reader, "global", 0);
        version = (int) globals.number(0xfb, 0);
        unsigned = globals.has(0) ? (Transaction) globals.field(0).decoded() : null;
        if (version == 0) {
            if (unsigned == null) fail("No unsigned transaction was provided");
            String[] forbidden = {"PSBT_GLOBAL_TX_VERSION", "PSBT_GLOBAL_FALLBACK_LOCKTIME", "PSBT_GLOBAL_INPUT_COUNT", "PSBT_GLOBAL_OUTPUT_COUNT", "PSBT_GLOBAL_TX_MODIFIABLE"};
            for (int type=2;type<=6;type++) if (globals.has(type)) fail(forbidden[type-2]+" is not allowed in PSBTv0");
        }
        if (version == 1) fail("There is no PSBT version 1");
        if (version == 2) {
            for (int type : List.of(2,4,5)) if (!globals.has(type)) fail(switch(type) {case 2 -> "PSBT_GLOBAL_TX_VERSION";case 4 -> "PSBT_GLOBAL_INPUT_COUNT";default -> "PSBT_GLOBAL_OUTPUT_COUNT";}+" is required in PSBTv2");
            if (unsigned != null) fail("PSBT_GLOBAL_UNSIGNED_TX is not allowed in PSBTv2");
        }
        long inputCount = version == 0 ? unsigned.inputs().size() : compactValue(globals.field(4));
        long outputCount = version == 0 ? unsigned.outputs().size() : compactValue(globals.field(5));
        while (reader.remaining() > 0 && inputs.size() < inputCount) {
            Fields input = map(reader, "input", version); int index=inputs.size();
            if (input.has(0)) {
                Transaction previous = (Transaction) input.field(0).decoded();
                Hash256 txid = version == 0 ? unsigned.inputs().get(index).previousOutput().transactionId() : new Hash256(input.field(0x0e).value());
                long vout = version == 0 ? unsigned.inputs().get(index).previousOutput().outputIndex().value() : input.number(0x0f,0);
                if (!previous.txId().equals(txid)) fail("Non-witness UTXO does not match outpoint hash");
                if (vout >= previous.outputs().size()) fail("Input specifies output index that does not exist");
            }
            inputs.add(input);
        }
        if (inputs.size() != inputCount) fail("Inputs provided does not match the number of inputs in transaction.");
        while (reader.remaining() > 0 && outputs.size() < outputCount) outputs.add(map(reader,"output",version));
        if (outputs.size() != outputCount) fail("Outputs provided does not match the number of outputs in transaction.");
        if (reader.remaining() != 0) throw new FormatError("extra data after PSBT");
    }

    private static long compactValue(Field field) { return (Long) field.decoded(); }

    Optional<Transaction> transaction(boolean identity) {
        if(version==0)return Optional.of(unsigned);
        boolean heightAllowed=true,timeAllowed=true;long height=0,time=0;
        for(Fields input:inputs){
            if(input.has(0x11)&&!input.has(0x12))heightAllowed=false;
            else if(!input.has(0x11)&&input.has(0x12))timeAllowed=false;
            if(!heightAllowed&&!timeAllowed)return Optional.empty();
            height=Math.max(height,input.number(0x12,0));time=Math.max(time,input.number(0x11,0));
        }
        long lock=heightAllowed&&height>0?height:timeAllowed&&time>0?time:globals.number(3,0);
        var vin=new ArrayList<TxIn>();for(Fields input:inputs)vin.add(new TxIn(new OutPoint(new Hash256(input.field(0x0e).value()),new UInt32(input.number(0x0f,0))),new byte[0],new UInt32(identity?0:input.number(0x10,0xffff_ffffL))));
        var vout=new ArrayList<TxOut>();for(Fields output:outputs)vout.add(new TxOut(output.number(3,0),output.field(4).value()));
        return Optional.of(new Transaction((int)globals.number(2,0),vin,vout,new UInt32(lock)));
    }

    private static Fields map(Reader reader, String scope, int version) {
        var result = new Fields(); boolean separator = false;
        while (reader.remaining() > 0) {
            byte[] key=reader.vector(); if (key.length == 0) {separator=true;break;}
            String keyHex=HexFormat.of().formatHex(key);
            if (result.containsKey(keyHex)) fail("Duplicate Key, "+scope+" key \""+keyHex+"\" already provided");
            var keyReader=new Reader(key);int type=(int)keyReader.compact();
            Field field = scope.equals("global") ? global(reader,type,key,keyReader) : scope.equals("input") ? input(reader,type,key,keyReader,version) : output(reader,type,key,keyReader,version);
            result.put(keyHex,field);
        }
        if (!separator) fail(scope.equals("global") ? "Separator is missing at the end of the global map" : "Separator is missing at the end of an "+scope+" map");
        if (version >= 2 && !scope.equals("global")) {
            int first=scope.equals("input")?0x0e:3, second=scope.equals("input")?0x0f:4;
            if(!result.has(first)) fail(scope.equals("input")?"Previous TXID is required in PSBTv2":"Output amount is required in PSBTv2");
            if(!result.has(second)) fail(scope.equals("input")?"Previous output's index is required in PSBTv2":"Output script is required in PSBTv2");
        }
        return result;
    }

    private static Field global(Reader reader,int type,byte[] key,Reader keyReader) {
        switch(type) {
            case 0: {
                keySize(key,1,"Global Unsigned TX");Field field=typed(reader,type,key,r->transaction(r,false));
                Transaction tx=(Transaction)field.decoded();
                if(tx.inputs().stream().anyMatch(in->in.scriptSigLength()!=0||in.witness().size()!=0)) fail("Unsigned tx does not have empty scriptSigs and scriptWitnesses.");
                return field;
            }
            case 1:
                keySize(key,79,"Global XPUB"); validPubkey(Arrays.copyOfRange(key,46,79),"Invalid pubkey");return originField(reader,type,key);
            case 2,3,6,0xfb: {
                keySize(key,1,switch(type){case 2->"Global Transaction Version";case 3->"Global Fallback Locktime";case 6->"Global TX Modifiable Flags";default->"Global PSBT Version";});
                Field field=fixed(reader,type,key,type==6?1:4);
                if(type==0xfb && new Reader(field.value()).number(4)>2)fail("Unsupported version number");return field;
            }
            case 4,5:
                keySize(key,1,type==4?"Global Input Count":"Global Output Count");return typed(reader,type,key,Reader::compact);
            case 0xfc: proprietary(keyReader);break;
        }
        return vector(reader,type,key);
    }

    private static Field input(Reader reader,int type,byte[] key,Reader keyReader,int version) {
        switch(type) {
            case 0: keySize(key,1,"Input Non-witness UTXO");return typed(reader,type,key,r->transaction(r,true));
            case 1: keySize(key,1,"Input Witness UTXO");return typed(reader,type,key,PsbtDocument::txOut);
            case 2: {
                if(key.length!=34 && key.length!=66)fail("Size of key was not the expected size for the type partial signature pubkey");
                validPubkey(Arrays.copyOfRange(key,1,key.length),"Invalid pubkey");Field field=vector(reader,type,key);
                if(field.value().length==0 || !SignatureEncoding.isValidDerEncoding(field.value()) || !SignatureEncoding.isDefinedHashType(field.value()))fail("Signature is not a valid encoding");return field;
            }
            case 3: keySize(key,1,"Input Sighash Type");return fixed(reader,type,key,4);
            case 4,5,7,8: {
                keySize(key,1,switch(type){case 4->"Input redeemScript";case 5->"Input witnessScript";case 7->"Input Final scriptSig";default->"Input Final scriptWitness";});
                return type==8?typed(reader,type,key,PsbtDocument::witness):vector(reader,type,key);
            }
            case 6: return keyOrigin(reader,type,key);
            case 0x0a,0x0b,0x0c,0x0d:
                keySize(key,type==0x0a||type==0x0c?21:33,switch(type){case 0x0a->"Input RIPEMD160 Preimage";case 0x0b->"Input SHA256 Preimage";case 0x0c->"Input Hash160 Preimage";default->"Input Hash256 Preimage";});break;
            case 0x0e,0x0f,0x10,0x11,0x12: {
                String name=switch(type){case 0x0e->"Previous TXID";case 0x0f->"Previous Output's Index";case 0x10->"Sequence";case 0x11->"Required Time Based Locktime";default->"Required Height Based Locktime";};
                keySize(key,1,"Input "+name);
                if(version<2) fail(switch(type){case 0x0e->"Previous txid";case 0x0f->"Previous output's index";case 0x10->"Sequence";case 0x11->"Required time based locktime";default->"Required height based locktime";}+" is not allowed in PSBTv0");
                Field field=fixed(reader,type,key,type==0x0e?32:4);
                if(type==0x11||type==0x12){long lock=new Reader(field.value()).number(4);
                    if(type==0x11&&lock<500000000)fail("Required time based locktime is invalid (less than 500000000)");
                    if(type==0x12&&lock>=500000000)fail("Required height based locktime is invalid (greater than or equal to 500000000)");
                    if(type==0x12&&lock==0)fail("Required height based locktime is invalid (0)");}
                return field;
            }
            case 0x13,0x14: {
                String context=type==0x13?"key":"script";
                keySize(key,type==0x13?1:65,type==0x13?"Input Taproot Key Path Signature":"Input Taproot Script Path Signature");
                Field field=vector(reader,type,key);
                if(field.value().length<64)fail("Input Taproot "+context+" path signature is shorter than 64 bytes");
                if(field.value().length>65)fail("Input Taproot "+context+" path signature is longer than 65 bytes");return field;
            }
            case 0x15: {
                if(key.length<34)fail("Input Taproot leaf script key is not at least 34 bytes");
                if((key.length-2)%32!=0)fail("Input Taproot leaf script key's control block size is not valid");
                Field field=vector(reader,type,key);if(field.value().length==0)fail("Input Taproot leaf script must be at least 1 byte");return field;
            }
            case 0x16:return tapOrigin(reader,type,key,"Input");
            case 0x17,0x18:keySize(key,1,type==0x17?"Input Taproot Internal Key":"Input Taproot Merkle Root");return fixed(reader,type,key,32);
            case 0x1a:keySize(key,34,"Input MuSig2 Participants Pubkeys");return participants(reader,type,key,keyReader,"Input");
            case 0x1b,0x1c: {
                if(key.length!=67&&key.length!=99)fail(type==0x1b?"Input musig2 pubnonce key is not expected size of 67 or 99 bytes":"Input musig2 partial sig key is not expected size of 67 or 99 bytes");
                byte[] participant=keyReader.bytes(33),aggregate=keyReader.bytes(33);
                validPubkey(aggregate,"musig2 aggregate pubkey is invalid");validPubkey(participant,"musig2 participant pubkey is invalid");
                if(keyReader.remaining()>0)keyReader.bytes(32);
                Field field=type==0x1b?vector(reader,type,key):fixed(reader,type,key,32);
                if(type==0x1b&&field.value().length!=66)fail("Input musig2 pubnonce value is not 66 bytes");return field;
            }
            case 0xfc:proprietary(keyReader);break;
        }
        return vector(reader,type,key);
    }

    private static Field output(Reader reader,int type,byte[] key,Reader keyReader,int version) {
        switch(type) {
            case 0,1:keySize(key,1,type==0?"Output redeemScript":"Output witnessScript");break;
            case 2:return keyOrigin(reader,type,key);
            case 3,4:
                keySize(key,1,type==3?"Output Amount":"Output Script");
                if(version<2)fail(type==3?"Output amount is not allowed in PSBTv0":"Output script is not allowed in PSBTv0");
                return type==3?fixed(reader,type,key,8):vector(reader,type,key);
            case 5:keySize(key,1,"Output Taproot Internal Key");return fixed(reader,type,key,32);
            case 6: {
                keySize(key,1,"Output Taproot Tree Key");Field field=vector(reader,type,key);Reader tree=new Reader(field.value());
                if(tree.remaining()==0)fail("Output Taproot tree must not be empty");
                var depths=new ArrayList<Integer>();boolean valid=true;
                while(tree.remaining()>0){int depth=tree.byteValue(),leaf=tree.byteValue();tree.vector();
                    if(depth>128)fail("Output Taproot tree has as leaf greater than Taproot maximum depth");
                    if((leaf&1)!=0)fail("Output Taproot tree has a leaf with an invalid leaf version");
                    while(!depths.isEmpty()&&depths.getLast()==depth){depths.removeLast();depth--;}
                    if(depth<0||!depths.isEmpty()&&depths.getLast()>depth)valid=false;
                    depths.add(depth);
                }
                if(!valid||depths.size()!=1||depths.getFirst()!=0)fail("Output Taproot tree is malformed");return field;
            }
            case 7:return tapOrigin(reader,type,key,"Output");
            case 8:keySize(key,34,"Output MuSig2 Participants Pubkeys");return participants(reader,type,key,keyReader,"Output");
            case 0xfc:proprietary(keyReader);break;
        }
        return vector(reader,type,key);
    }

    private static void proprietary(Reader reader){reader.vector();reader.compact();}
    private static Field participants(Reader reader,int type,byte[] key,Reader keyReader,String context){
        validPubkey(keyReader.bytes(33),context+" musig2 aggregate pubkey is invalid");Field field=vector(reader,type,key);Reader values=new Reader(field.value());
        while(values.remaining()>=33)validPubkey(values.bytes(33),context+" musig2 participant pubkey is invalid");
        if(values.remaining()!=0)fail(context+" musig2 participants pubkeys value size is not a multiple of 33");return field;
    }
    private static Field keyOrigin(Reader reader,int type,byte[] key){
        if(key.length!=34&&key.length!=66)fail("Size of key was not the expected size for the type BIP32 keypath");
        validPubkey(Arrays.copyOfRange(key,1,key.length),"Invalid pubkey");return originField(reader,type,key);
    }
    private static Field originField(Reader reader,int type,byte[] key){long length=reader.compact();int start=reader.position;origin(reader,length);return new Field(type,key,reader.slice(start),null);}
    private static void origin(Reader reader,long length){if(length==0||length%4!=0)fail("Invalid length for HD key path");reader.bytes((int)length);}
    private static Field tapOrigin(Reader reader,int type,byte[] key,String scope){
        keySize(key,33,scope+" Taproot BIP32 Keypath");long length=reader.compact();int start=reader.position;long count=reader.compact();
        for(long i=0;i<count;i++)reader.bytes(32);
        int consumed=reader.position-start;if(consumed>length)fail(scope+" Taproot BIP32 keypath has an invalid length");
        origin(reader,length-consumed);return new Field(type,key,reader.slice(start),null);
    }
    private static Field fixed(Reader reader,int type,byte[] key,int size){return typed(reader,type,key,r->r.bytes(size));}
    private static Field typed(Reader reader,int type,byte[] key,java.util.function.Function<Reader,Object> decoder){
        long length=reader.compact();int start=reader.position;Object value=decoder.apply(reader);
        if(reader.position-start!=length)fail("Size of value was not the stated size");return new Field(type,key,reader.slice(start),value);
    }
    private static Field vector(Reader reader,int type,byte[] key){return new Field(type,key,reader.vector(),null);}
    private static void keySize(byte[] key,int size,String name){if(key.length!=size)fail("Size of key was not "+size+" for the type "+name);}
    private static void validPubkey(byte[] key,String message){try{PublicKey.fromBytes(key);}catch(IllegalArgumentException invalid){fail(message);}}

    private static Transaction transaction(Reader reader,boolean allowWitness){
        int version=(int)reader.number(4);long count=reader.compact();int flags=0;boolean outputsRead=false;
        if(count==0&&allowWitness){flags=reader.byteValue();if(flags!=0)count=reader.compact();else outputsRead=true;}
        var inputs=new ArrayList<TxIn>();
        for(long i=0;i<count;i++)inputs.add(new TxIn(new OutPoint(new Hash256(reader.bytes(32)),new UInt32(reader.number(4))),reader.vector(),new UInt32(reader.number(4))));
        var outputs=new ArrayList<TxOut>();long outputCount=outputsRead?0:reader.compact();
        for(long i=0;i<outputCount;i++)outputs.add(txOut(reader));
        if((flags&1)!=0){flags^=1;var witnessed=new ArrayList<TxIn>();boolean any=false;
            for(var input:inputs){Witness witness=witness(reader);any|=witness.size()!=0;witnessed.add(new TxIn(input.previousOutput(),input.scriptSig(),input.sequence(),witness));}
            if(!any)fail("Superfluous witness record");inputs=witnessed;
        }
        if(flags!=0)fail("Unknown transaction optional data");
        return new Transaction(version,inputs,outputs,new UInt32(reader.number(4)));
    }
    private static TxOut txOut(Reader reader){return new TxOut(reader.number(8),reader.vector());}
    private static Witness witness(Reader reader){long count=reader.compact();var items=new ArrayList<byte[]>();for(long i=0;i<count;i++)items.add(reader.vector());return new Witness(items);}

    static final class Reader {
        final byte[] data;int position;
        Reader(byte[] data){this.data=data;}
        int remaining(){return data.length-position;}
        int byteValue(){if(remaining()==0)fail("SpanReader::read(): end of data");return data[position++]&255;}
        byte[] bytes(int size){if(size<0||size>remaining())fail("SpanReader::read(): end of data");byte[] value=Arrays.copyOfRange(data,position,position+size);position+=size;return value;}
        byte[] slice(int start){return Arrays.copyOfRange(data,start,position);}
        long number(int size){byte[] bytes=bytes(size);long value=0;for(int i=0;i<size;i++)value|=(long)(bytes[i]&255)<<(8*i);return value;}
        long compact(){int marker=byteValue();long value=marker<253?marker:number(marker==253?2:marker==254?4:8);
            if(marker==253&&value<253||marker==254&&value<=65535||marker==255&&Long.compareUnsigned(value,0xffff_ffffL)<=0)fail("non-canonical ReadCompactSize()");
            if(value<0||value>0x02000000)fail("ReadCompactSize(): size too large");return value;}
        byte[] vector(){return bytes((int)compact());}
    }
    private static final class FormatError extends RuntimeException {FormatError(String message){super(message);}}
    private static void fail(String message){throw new FormatError(message+": iostream error");}
}

package ru.bitcoin.node.app.rpc;

// PSBT signing data lookup: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.transaction.Transaction;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.Fragment;
import static ru.bitcoin.node.app.rpc.MiniscriptSatisfaction.*;

/** Signature/preimage availability from one PSBT input, with no wallet key access. */
final class PsbtMiniscriptContext implements Context<String> {
    private final PsbtDocument document;
    final boolean estimating;
    final PsbtMissing missing;
    final DescriptorProvider provider;
    final boolean output;
    private final Fields input;private final Transaction transaction;private final int index;private final byte[] leaf;
    private final Map<String,byte[]> signatures=new HashMap<>();private final Map<String,String> publicKeys=new HashMap<>();
    PsbtMiniscriptContext(PsbtDocument document,int index,byte[] leaf){
        this(document,index,leaf,false,new PsbtMissing());
    }
    PsbtMiniscriptContext(PsbtDocument document,int index,byte[] leaf,boolean estimating,PsbtMissing missing){
        this(document,index,leaf,estimating,missing,null,false);
    }
    PsbtMiniscriptContext(PsbtDocument document,int index,byte[] leaf,boolean estimating,PsbtMissing missing,DescriptorProvider provider,boolean output){
        this.estimating=estimating; this.missing=missing;
        this.provider=provider;this.output=output;
        this.document=document;
        this.input=document.inputs.get(index);this.transaction=document.transaction(false).orElse(null);this.index=index;this.leaf=leaf==null?null:leaf.clone();
        for(Field field:input.values())if(field.type()==2){byte[] key=Arrays.copyOfRange(field.key(),1,field.key().length);String id=hex(Hash160.hash(key));publicKeys.putIfAbsent(id,hex(key));signatures.putIfAbsent(id,field.value());}
        for(Field field:input.values())if(field.type()==6){byte[] key=Arrays.copyOfRange(field.key(),1,field.key().length);publicKeys.putIfAbsent(hex(Hash160.hash(key)),hex(key));}
        for(Field field:input.values())if(field.type()==0x16){byte[] xonly=Arrays.copyOfRange(field.key(),1,33),key=new byte[33];key[0]=2;System.arraycopy(xonly,0,key,1,32);publicKeys.putIfAbsent(hex(Hash160.hash(xonly)),hex(key));}
    }
    @Override public boolean taproot(){return leaf!=null;}
    @Override public String fromPublicKey(byte[] key){
        if(taproot())return key.length==32?hex(key):null;
        return key.length==33&&(key[0]==2||key[0]==3)||key.length==65&&(key[0]==4||key[0]==6||key[0]==7)?hex(key):null;
    }
    @Override public String fromKeyHash(byte[] hash){String key=publicKeys.get(hex(hash));if(key==null&&provider!=null){byte[] known=provider.publicKey(hash);if(known!=null)key=hex(known);}
        if(key==null){missing.publicKeys.add(hex(hash));return null;}return taproot()?key.substring(2,66):key;}
    @Override public byte[] publicKey(String key){return HexFormat.of().parseHex(key);}
    @Override public String keyText(String key){return key;}
    @Override public Item sign(String key){
        if(provider!=null){if(taproot())provider.tapOrigin(input,publicKey(key),leaf,false);else provider.legacyOrigin(input,publicKey(key),false);}
        byte[] signature;
        if(taproot()){Field field=input.get("14"+key+hex(leaf));if(field==null&&provider!=null)provider.musigOrigins(input,leaf);signature=field==null?(estimating?new byte[64]:PsbtMuSig2.aggregate(document,index,publicKey(key),leaf,null)):field.value();}
        else signature=signatures.get(hex(Hash160.hash(publicKey(key))));
        if(signature==null&&!taproot()){
            if(estimating){signature=dummySignature();signatures.put(hex(Hash160.hash(publicKey(key))),signature);}
            else missing.signatures.add(hex(Hash160.hash(publicKey(key))));
        }
        return signature==null?new Item(Availability.NO,new byte[0]):new Item(Availability.YES,signature);
    }
    @Override public Item preimage(Fragment fragment,byte[] hash){
        int type=switch(fragment){case RIPEMD160->0x0a;case SHA256->0x0b;case HASH160->0x0c;case HASH256->0x0d;default->throw new IllegalArgumentException("Not a hash challenge");};
        Field field=input.get(String.format("%02x",type)+hex(hash));return field==null?new Item(Availability.NO,new byte[0]):new Item(Availability.YES,field.value());
    }
    @Override public boolean checkAfter(long lock){
        if(estimating)return true;
        if(transaction==null||(transaction.lockTime().value()<500000000)!=(lock<500000000))return false;
        return lock<=transaction.lockTime().value()&&transaction.inputs().get(index).sequence().value()!=0xffff_ffffL;
    }
    @Override public boolean checkOlder(long lock){
        if(estimating)return true;
        if(transaction==null||Integer.toUnsignedLong(transaction.version())<2)return false;
        long sequence=transaction.inputs().get(index).sequence().value();if((sequence&0x8000_0000L)!=0)return false;
        long mask=(1L<<22)|0xffff;sequence&=mask;lock&=mask;
        return (sequence&(1L<<22))==(lock&(1L<<22))&&lock<=sequence;
    }
    static byte[] dummySignature(){
        byte[] signature=new byte[71];signature[0]=0x30;signature[1]=68;signature[2]=2;signature[3]=32;signature[4]=1;signature[36]=2;signature[37]=32;signature[38]=1;signature[70]=1;return signature;
    }
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
}

package ru.bitcoin.node.app.rpc;

// PSBT updater semantics: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.crypto.hash.Hash160;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;

/** Public-data processing shared by the node's PSBT roles. */
final class PsbtUpdater {
    interface Coins {
        Optional<Transaction> transaction(Hash256 txid);
        Optional<TxOut> coin(OutPoint point);
    }
    static final Coins EMPTY=new Coins(){public Optional<Transaction> transaction(Hash256 txid){return Optional.empty();}public Optional<TxOut> coin(OutPoint point){return Optional.empty();}};
    static String update(String encoded,List<?> descriptors,NetworkParameters network,Coins coins) {
        var provider=DescriptorProvider.parse(descriptors,network);
        var document=PsbtDocument.parse(encoded);var transaction=document.transaction(false);
        // Even an invalid v2 locktime combination still has fully specified prevouts.
        var points=document.version==0?document.unsigned.inputs().stream().map(TxIn::previousOutput).toList():document.inputs.stream().map(input->new OutPoint(new Hash256(input.field(0x0e).value()),new UInt32(input.number(0x0f,0)))).toList();
        for(int index=0;index<document.inputs.size();index++) {
            Fields input=document.inputs.get(index);if(input.has(0))continue;OutPoint point=points.get(index);
            var previous=coins.transaction(point.transactionId());
            if(previous.isPresent()){Transaction tx=previous.get();input.put("00",new Field(0,new byte[]{0},TransactionSerializer.serialize(tx),tx));}
            else coins.coin(point).filter(coin->segwit(coin.scriptPubKey(),provider)).ifPresent(coin->input.put("01",new Field(1,new byte[]{1},encodeCoin(coin),coin)));
        }
        if(transaction.isEmpty())throw new RpcException(-25,"The transaction cannot be valid");
        var verifier=new PsbtScriptVerifier(document);var finalizer=new PsbtFinalizer(document);
        for(int index=0;index<document.inputs.size();index++) {
            Fields input=document.inputs.get(index);
            if(input.has(7)&&input.field(7).value().length!=0||input.has(8)&&!((Witness)input.field(8).decoded()).isEmpty())continue;
            TxOut coin=verifier.coin(index);if(coin==null)continue;
            // Verification precedes sighash checks in Core, including trivially spendable outputs.
            if(verifier.signedAndVerified(input,index))continue;
            long sighash=isTaproot(coin.scriptPubKey())?0:1;
            if(input.has(3)&&input.number(3,0)!=sighash)throw sighashMismatch();
            for(Field field:input.values()) {
                byte[] signature=field.value();
                if(field.type()==0x13||field.type()==0x14){if(sighash==0?signature.length!=64:signature.length!=65||(signature[64]&255)!=sighash)throw sighashMismatch();}
                else if(field.type()==2&&sighash!=0&&(signature[signature.length-1]&255)!=sighash)throw sighashMismatch();
            }
            finalizer.signInput(index,false,false,null,provider,false);
        }
        for(int index=0;index<document.outputs.size();index++)updateOutput(document.outputs.get(index),transaction.get().outputs().get(index),provider);
        boolean drop=true;
        for(Fields input:document.inputs){if(!input.has(1)||!(input.field(1).decoded() instanceof TxOut coin)||!witness(coin.scriptPubKey())||coin.scriptPubKey()[0]==0||(input.number(3,0)&0x80)!=0){drop=false;break;}}
        if(drop)for(Fields input:document.inputs)input.remove("00");
        return PsbtCodec.encode(document);
    }
    private static RpcException sighashMismatch(){return new RpcException(-22,"Specified sighash value does not match value stored in PSBT");}
    private static boolean isTaproot(byte[] script){return script.length==34&&script[0]==0x51&&script[1]==32;}
    private static boolean witness(byte[] script){return script.length>=4&&script.length<=42&&(script[0]==0||script[0]>=0x51&&script[0]<=0x60)&&(script[1]&255)==script.length-2;}
    private static boolean segwit(byte[] script,DescriptorProvider provider){if(witness(script))return true;if(script.length==23&&script[0]==(byte)0xa9&&script[1]==20&&script[22]==(byte)0x87){byte[] redeem=provider.scripts.get(HexFormat.of().formatHex(Arrays.copyOfRange(script,2,22)));return redeem!=null&&witness(redeem);}return false;}
    private static void updateOutput(Fields output,TxOut coin,DescriptorProvider provider) {
        // Core uses a hypothetical spend to collect output metadata. Reuse the same public-data signer.
        var previous=new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0)),new byte[0],new UInt32(0))),List.of(coin),new UInt32(0));
        var unsigned=new Transaction(2,List.of(new TxIn(new OutPoint(previous.txId(),new UInt32(0)),new byte[0],new UInt32(0))),List.of(new TxOut(0,new byte[]{0x6a})),new UInt32(0));
        var document=PsbtDocument.parse(PsbtCreator.create(unsigned,0));var input=document.inputs.getFirst();
        input.put("00",new Field(0,new byte[]{0},TransactionSerializer.serialize(previous),previous));
        Map<Integer,Integer> mapping=Map.of(0,4,1,5,2,6,5,0x17,7,0x16,8,0x1a);
        for(Field field:output.values())if(mapping.containsKey(field.type())){int type=mapping.get(field.type());byte[] key=field.key().clone();key[0]=(byte)type;input.put(HexFormat.of().formatHex(key),new Field(type,key,field.value(),field.decoded()));}
        boolean existingTree=DescriptorProvider.importOutputTree(output,input);
        new PsbtFinalizer(document).signInput(0,false,false,null,provider,false);
        var reverse=new HashMap<Integer,Integer>();mapping.forEach((a,b)->reverse.put(b,a));
        for(Field field:input.values())if(reverse.containsKey(field.type())){int type=reverse.get(field.type());byte[] key=field.key().clone();key[0]=(byte)type;output.putIfAbsent(HexFormat.of().formatHex(key),new Field(type,key,field.value(),null));}
        if(existingTree) {
            var reader=new Reader(output.field(6).value());var tuples=new ArrayList<byte[]>();
            while(reader.remaining()>0){int start=reader.position;reader.bytes(2);reader.vector();tuples.add(reader.slice(start));}
            var tree=new ByteArrayOutputStream();for(byte[] tuple:tuples.reversed())tree.writeBytes(tuple);
            output.put("06",new Field(6,new byte[]{6},tree.toByteArray(),null));
        }
        if(isTaproot(coin.scriptPubKey()))provider.taproot(output,Arrays.copyOfRange(coin.scriptPubKey(),2,34),true);
    }
    private static byte[] encodeCoin(TxOut coin){var out=new ByteArrayOutputStream();for(int i=0;i<8;i++)out.write((int)(coin.value()>>>(8*i))&255);byte[] script=coin.scriptPubKey();out.writeBytes(CompactSize.encode(script.length));out.writeBytes(script);return out.toByteArray();}
}

package ru.bitcoin.node.app.rpc;

// Descriptor signing-provider semantics: Copyright (c) 2017-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;

/** Flat public signing provider. Secrets are used for hardened expansion, never exposed in PSBT metadata. */
final class DescriptorProvider {
    record Leaf(byte[] script, int depth, byte[] control) { }
    record Taproot(byte[] internal,byte[] root,List<Leaf> leaves) { }
    private record Tree(byte[] hash,int depth,List<Integer> leaves) { }
    final Map<String,DescriptorKey.Derived> keys=new LinkedHashMap<>();
    final Map<String,byte[]> publicKeys=new LinkedHashMap<>();
    final Map<String,byte[]> scripts=new LinkedHashMap<>();
    final Map<String,Taproot> taproots=new LinkedHashMap<>();
    final Map<String,List<byte[]>> participants=new LinkedHashMap<>();
    static DescriptorProvider parse(List<?> descriptors,NetworkParameters network) {
        var result=new DescriptorProvider();var available=new HashMap<String,PrivateKey>();
        for(Object value:descriptors) {
            String text;long low=0,high=1000;
            if(value instanceof String string)text=string;
            else if(value instanceof Map<?,?> object) {
                Object desc=object.get("desc");if(desc==null)throw new RpcException(-8,"Descriptor needs to be provided in scan object");
                if(!(desc instanceof String))throw PsbtCodec.stringType(desc);text=(String)desc;
                if(object.get("range")!=null){long[] range=DescriptorRpc.range(object.get("range"));low=range[0];high=range[1];}
            }else throw new RpcException(-8,"Scan object needs to be either a string or an object");
            var parsed=OutputDescriptor.parse(text,false,network,available);available.putAll(parsed.privateKeys());
            if(!parsed.descriptors().getFirst().ranged()){low=0;high=0;}
            for(long position=low;position<=high;position++)for(var descriptor:parsed.descriptors())
                if(!result.expand(descriptor,position,parsed.privateKeys()))throw new RpcException(-5,"Cannot derive script without private keys: '"+text+"'");
        }
        return result;
    }
    private boolean expand(OutputDescriptor descriptor,long position,Map<String,PrivateKey> available) {
        var outputs=descriptor.expand(position,available);if(outputs.isEmpty())return false;
        var derived=new ArrayList<DescriptorKey.Derived>();
        for(var key:descriptor.keys){var value=key.derive(position,available);if(value.isEmpty())return false;derived.add(value.get());add(value.get());}
        if(descriptor.kind==OutputDescriptor.Kind.MINISCRIPT&&descriptor.taprootScript)for(var key:derived)publicKeys.putIfAbsent(hex(Hash160.hash(xonly(key.publicKey()))),key.publicKey());
        for(var child:descriptor.children)if(!expand(child,position,available))return false;
        if(descriptor.kind==OutputDescriptor.Kind.SH||descriptor.kind==OutputDescriptor.Kind.WSH)
            for(byte[] script:descriptor.children.getFirst().expand(position,available).orElseThrow())scripts.putIfAbsent(hex(Hash160.hash(script)),script);
        if(descriptor.kind==OutputDescriptor.Kind.COMBO&&derived.getFirst().publicKey().length==33){byte[] witness=join(new byte[]{0,20},Hash160.hash(derived.getFirst().publicKey()));scripts.putIfAbsent(hex(Hash160.hash(witness)),witness);}
        if(descriptor.kind==OutputDescriptor.Kind.TR) {
            byte[] internal=xonly(derived.getFirst().publicKey());var leaves=new ArrayList<byte[]>();var proofs=new ArrayList<List<byte[]>>();var stack=new ArrayList<Tree>();
            for(int i=0;i<descriptor.children.size();i++) {
                byte[] script=descriptor.children.get(i).expand(position,available).orElseThrow().getFirst();leaves.add(script);proofs.add(new ArrayList<>());
                stack.add(new Tree(TaggedHash.hash("TapLeaf",new byte[]{(byte)0xc0},CompactSize.encode(script.length),script),descriptor.depths.get(i),List.of(i)));
                while(stack.size()>1&&stack.getLast().depth==stack.get(stack.size()-2).depth) {
                    var right=stack.removeLast();var left=stack.removeLast();left.leaves.forEach(n->proofs.get(n).add(right.hash));right.leaves.forEach(n->proofs.get(n).add(left.hash));
                    var indices=new ArrayList<>(left.leaves);indices.addAll(right.leaves);
                    byte[] hash=Arrays.compareUnsigned(left.hash,right.hash)<0?TaggedHash.hash("TapBranch",left.hash,right.hash):TaggedHash.hash("TapBranch",right.hash,left.hash);
                    stack.add(new Tree(hash,left.depth-1,indices));
                }
            }
            byte[] root=stack.isEmpty()?new byte[32]:stack.getFirst().hash;
            byte[] tweak=stack.isEmpty()?TaggedHash.hash("TapTweak",internal):TaggedHash.hash("TapTweak",internal,root);
            var point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(internal).add(Secp256k1.DOMAIN.getG().multiply(new java.math.BigInteger(1,tweak))).normalize();
            var data=new ArrayList<Leaf>();for(int i=0;i<leaves.size();i++){var control=new ByteArrayOutputStream();control.write(point.getAffineYCoord().toBigInteger().testBit(0)?0xc1:0xc0);control.writeBytes(internal);proofs.get(i).forEach(control::writeBytes);data.add(new Leaf(leaves.get(i),descriptor.depths.get(i),control.toByteArray()));}
            taproots.putIfAbsent(hex(point.getAffineXCoord().getEncoded()),new Taproot(internal,root,List.copyOf(data)));
        }
        return true;
    }
    private void add(DescriptorKey.Derived key) {
        if(key.explicitOrigin())keys.put(hex(Hash160.hash(key.publicKey())),key);else keys.putIfAbsent(hex(Hash160.hash(key.publicKey())),key);
        publicKeys.putIfAbsent(hex(Hash160.hash(key.publicKey())),key.publicKey());
        if(key.aggregate()!=null)participants.putIfAbsent(hex(key.aggregate()),key.participants().stream().map(DescriptorKey.Derived::publicKey).toList());
        key.participants().forEach(this::add);
    }
    byte[] publicKey(byte[] hash) {return publicKeys.get(hex(hash));}
    DescriptorKey.Derived xonlyKey(byte[] xonly) {
        for(int prefix:new int[]{2,3}){var key=keys.get(hex(Hash160.hash(join(new byte[]{(byte)prefix},xonly))));if(key!=null)return key;}return null;
    }
    void legacyOrigin(Fields fields,byte[] publicKey,boolean output) {
        if(!output&&fields.containsKey("02"+hex(publicKey)))return;
        var key=keys.get(hex(Hash160.hash(publicKey)));if(key!=null)putIfAbsent(fields,output?2:6,publicKey,origin(key));
    }
    void tapOrigin(Fields fields,byte[] publicKey,byte[] leaf,boolean output) {
        var key=xonlyKey(publicKey);if(key==null)return;int type=output?7:0x16;String id=String.format("%02x",type)+hex(publicKey);
        var hashes=new TreeSet<String>((a,b)->Arrays.compareUnsigned(HexFormat.of().parseHex(a),HexFormat.of().parseHex(b)));byte[] origin=origin(key);
        Field existing=fields.get(id);if(existing!=null){var reader=new Reader(existing.value());long count=reader.compact();for(long i=0;i<count;i++)hashes.add(hex(reader.bytes(32)));origin=reader.bytes(reader.remaining());}
        if(leaf!=null)hashes.add(hex(leaf));var value=new ByteArrayOutputStream();value.writeBytes(CompactSize.encode(hashes.size()));for(String hash:hashes)value.writeBytes(HexFormat.of().parseHex(hash));value.writeBytes(origin);
        byte[] serializedKey=join(new byte[]{(byte)type},publicKey);fields.put(id,new Field(type,serializedKey,value.toByteArray(),null));
    }
    void taproot(Fields fields,byte[] outputKey,boolean output) {
        var data=taproots.get(hex(outputKey));
        if(data!=null) {
            putIfAbsent(fields,output?5:0x17,new byte[0],data.internal);
            if(!output&&!data.leaves.isEmpty())putIfAbsent(fields,0x18,new byte[0],data.root);
            if(output&&!data.leaves.isEmpty()) {var tree=new ByteArrayOutputStream();for(var leaf:data.leaves.reversed()){tree.write(leaf.depth);tree.write(0xc0);tree.writeBytes(CompactSize.encode(leaf.script.length));tree.writeBytes(leaf.script);}fields.put("06",new Field(6,new byte[]{6},tree.toByteArray(),null));}
            if(!output)for(var leaf:data.leaves)putIfAbsent(fields,0x15,leaf.control,join(leaf.script,new byte[]{(byte)0xc0}));
        }
        for(var entry:participants.entrySet())putIfAbsent(fields,output?8:0x1a,HexFormat.of().parseHex(entry.getKey()),join(entry.getValue().toArray(byte[][]::new)));
        if(fields.has(output?5:0x17))tapOrigin(fields,fields.field(output?5:0x17).value(),null,output);
        tapOrigin(fields,outputKey,null,output);
    }
    void musigOrigins(Fields fields,byte[] leaf) {
        for(var group:participants.values())for(byte[] publicKey:group)tapOrigin(fields,xonly(publicKey),leaf,false);
    }
    static boolean importOutputTree(Fields output,Fields input) {
        if(!output.has(5)||!output.has(6))return false;
        byte[] internal=output.field(5).value();org.bouncycastle.math.ec.ECPoint point;
        try{point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(internal);}catch(IllegalArgumentException invalid){return false;}
        if(point==null)return false;
        var reader=new Reader(output.field(6).value());var leaves=new ArrayList<byte[]>();var versions=new ArrayList<Integer>();var proofs=new ArrayList<List<byte[]>>();var stack=new ArrayList<Tree>();
        while(reader.remaining()>0) {
            int depth=reader.bytes(1)[0]&255,version=reader.bytes(1)[0]&255;byte[] script=reader.vector();int index=leaves.size();leaves.add(script);versions.add(version);proofs.add(new ArrayList<>());
            stack.add(new Tree(TaggedHash.hash("TapLeaf",new byte[]{(byte)version},CompactSize.encode(script.length),script),depth,List.of(index)));
            while(stack.size()>1&&stack.getLast().depth==stack.get(stack.size()-2).depth){var right=stack.removeLast();var left=stack.removeLast();left.leaves.forEach(n->proofs.get(n).add(right.hash));right.leaves.forEach(n->proofs.get(n).add(left.hash));var indices=new ArrayList<>(left.leaves);indices.addAll(right.leaves);byte[] hash=Arrays.compareUnsigned(left.hash,right.hash)<0?TaggedHash.hash("TapBranch",left.hash,right.hash):TaggedHash.hash("TapBranch",right.hash,left.hash);stack.add(new Tree(hash,left.depth-1,indices));}
        }
        if(stack.isEmpty())return false;byte[] root=stack.getFirst().hash;
        var scalar=new java.math.BigInteger(1,TaggedHash.hash("TapTweak",internal,root));if(scalar.compareTo(Secp256k1.N)>=0)return false;
        point=point.add(Secp256k1.DOMAIN.getG().multiply(scalar)).normalize();if(point.isInfinity())return false;
        putIfAbsent(input,0x18,new byte[0],root);
        for(int i=0;i<leaves.size();i++){var control=new ByteArrayOutputStream();control.write(versions.get(i)|(point.getAffineYCoord().toBigInteger().testBit(0)?1:0));control.writeBytes(internal);proofs.get(i).forEach(control::writeBytes);putIfAbsent(input,0x15,control.toByteArray(),join(leaves.get(i),new byte[]{versions.get(i).byteValue()}));}
        return true;
    }
    private static byte[] origin(DescriptorKey.Derived key){var out=new ByteArrayOutputStream();out.writeBytes(key.fingerprint());for(long part:key.path())for(int i=0;i<4;i++)out.write((int)(part>>>(8*i))&255);return out.toByteArray();}
    static void putIfAbsent(Fields fields,int type,byte[] suffix,byte[] value){byte[] key=join(new byte[]{(byte)type},suffix);fields.putIfAbsent(hex(key),new Field(type,key,value,null));}
    private static byte[] xonly(byte[] key){return Arrays.copyOfRange(key,1,33);}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static byte[] join(byte[]... parts){var out=new ByteArrayOutputStream();for(byte[] part:parts)out.writeBytes(part);return out.toByteArray();}
}

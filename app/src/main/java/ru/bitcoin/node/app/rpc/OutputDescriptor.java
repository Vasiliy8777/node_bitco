package ru.bitcoin.node.app.rpc;

// Output descriptor semantics: Copyright (c) 2017-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.address.*;
import ru.bitcoin.node.protocol.network.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.DescriptorKey.Context;

/** Parsed descriptors share key providers, canonical text and script expansion across RPC roles. */
final class OutputDescriptor {
    enum Kind { PK,PKH,WPKH,MULTI,SORTEDMULTI,MULTI_A,SORTEDMULTI_A,SH,WSH,TR,RAWTR,COMBO,ADDR,RAW,UNUSED,MINISCRIPT }
    record Parsed(List<OutputDescriptor> descriptors,String checksum,Map<String,PrivateKey> privateKeys) { }
    private record Branch<T>(T value,int depth) { }
    final Kind kind;
    final List<DescriptorKey> keys;
    final List<OutputDescriptor> children;
    final long threshold;
    final byte[] raw;
    final String address;
    final Miniscript.Node<Integer> miniscript;
    final List<Integer> depths;
    final boolean taprootScript;

    private OutputDescriptor(Kind kind,List<DescriptorKey> keys,List<OutputDescriptor> children,long threshold,
                              byte[] raw,String address,Miniscript.Node<Integer> miniscript,List<Integer> depths,boolean taprootScript) {
        this.kind=kind;this.keys=List.copyOf(keys);this.children=List.copyOf(children);this.threshold=threshold;
        this.raw=raw;this.address=address;this.miniscript=miniscript;this.depths=List.copyOf(depths);this.taprootScript=taprootScript;
    }
    boolean ranged(){return keys.stream().anyMatch(DescriptorKey::ranged)||children.stream().anyMatch(OutputDescriptor::ranged);}
    boolean solvable(){return kind!=Kind.ADDR&&kind!=Kind.RAW&&children.stream().allMatch(OutputDescriptor::solvable);}
    String text() {
        if(kind==Kind.MINISCRIPT)return MiniscriptEncoding.text(miniscript,context(null));
        var arguments=new ArrayList<String>();
        if(kind==Kind.RAW)arguments.add(HexFormat.of().formatHex(raw));
        else if(kind==Kind.ADDR)arguments.add(address);
        else if(kind==Kind.TR){arguments.add(keys.getFirst().text());if(!children.isEmpty())arguments.add(treeText());}
        else {
            if(kind==Kind.MULTI||kind==Kind.SORTEDMULTI||kind==Kind.MULTI_A||kind==Kind.SORTEDMULTI_A)arguments.add(Long.toString(threshold));
            keys.forEach(key->arguments.add(key.text()));children.forEach(child->arguments.add(child.text()));
        }
        return kind.name().toLowerCase(Locale.ROOT)+"("+String.join(",",arguments)+")";
    }
    private String treeText() {
        var stack=new ArrayList<Branch<String>>();
        for(int i=0;i<children.size();i++) {
            stack.add(new Branch<>(children.get(i).text(),depths.get(i)));
            while(stack.size()>1&&stack.getLast().depth==stack.get(stack.size()-2).depth){var right=stack.removeLast();var left=stack.removeLast();stack.add(new Branch<>("{"+left.value+","+right.value+"}",left.depth-1));}
        }
        return stack.getFirst().value;
    }
    Optional<List<byte[]>> expand(long position,Map<String,PrivateKey> available) {
        var derived=new ArrayList<byte[]>();
        for(var key:keys){var value=key.derive(position,available);if(value.isEmpty())return Optional.empty();derived.add(value.get().publicKey());}
        var subs=new ArrayList<byte[]>();for(var child:children){var expanded=child.expand(position,available);if(expanded.isEmpty())return Optional.empty();subs.addAll(expanded.get());}
        var result=new ArrayList<byte[]>();
        switch(kind) {
            case RAW,ADDR->result.add(raw.clone());
            case UNUSED->{ }
            case PK->result.add(join(MiniscriptEncoding.push(taprootScript?xonly(derived.getFirst()):derived.getFirst()),new byte[]{(byte)0xac}));
            case PKH->result.add(pkh(derived.getFirst()));
            case WPKH->result.add(witness(0,Hash160.hash(derived.getFirst())));
            case SH->result.add(sh(subs.getFirst()));
            case WSH->result.add(witness(0,Sha256.hash(subs.getFirst())));
            case COMBO->{
                byte[] key=derived.getFirst();result.add(join(MiniscriptEncoding.push(key),new byte[]{(byte)0xac}));result.add(pkh(key));
                if(key.length==33){byte[] witness=witness(0,Hash160.hash(key));result.add(witness);result.add(sh(witness));}
            }
            case RAWTR->result.add(witness(1,xonly(derived.getFirst())));
            case TR->{
                byte[] internal=xonly(derived.getFirst()),root=null;
                if(!subs.isEmpty()) {
                    var stack=new ArrayList<Branch<byte[]>>();
                    for(int i=0;i<subs.size();i++) {
                        byte[] script=subs.get(i);stack.add(new Branch<>(TaggedHash.hash("TapLeaf",new byte[]{(byte)0xc0},CompactSize.encode(script.length),script),depths.get(i)));
                        while(stack.size()>1&&stack.getLast().depth==stack.get(stack.size()-2).depth){var right=stack.removeLast();var left=stack.removeLast();stack.add(new Branch<>(tapBranch(left.value,right.value),left.depth-1));}
                    }
                    root=stack.getFirst().value;
                }
                byte[] tweak=root==null?TaggedHash.hash("TapTweak",internal):TaggedHash.hash("TapTweak",internal,root);
                var scalar=new java.math.BigInteger(1,tweak);if(scalar.compareTo(Secp256k1.N)>=0)return Optional.empty();
                var point=ru.bitcoin.node.crypto.SchnorrSignature.liftX(internal).add(Secp256k1.DOMAIN.getG().multiply(scalar)).normalize();
                if(point.isInfinity())return Optional.empty();result.add(witness(1,point.getAffineXCoord().getEncoded()));
            }
            case MULTI,SORTEDMULTI,MULTI_A,SORTEDMULTI_A->{
                boolean multiA=kind==Kind.MULTI_A||kind==Kind.SORTEDMULTI_A;
                if(multiA)derived.replaceAll(OutputDescriptor::xonly);
                if(kind==Kind.SORTEDMULTI||kind==Kind.SORTEDMULTI_A)derived.sort(Arrays::compareUnsigned);
                var script=new ByteArrayOutputStream();if(!multiA)script.writeBytes(MiniscriptEncoding.number(threshold));
                for(int i=0;i<derived.size();i++){script.writeBytes(MiniscriptEncoding.push(derived.get(i)));if(multiA)script.write(i==0?0xac:0xba);}
                script.writeBytes(MiniscriptEncoding.number(multiA?threshold:derived.size()));script.write(multiA?0x9c:0xae);result.add(script.toByteArray());
            }
            case MINISCRIPT->result.add(MiniscriptEncoding.script(miniscript,context(derived)));
        }
        return Optional.of(result);
    }
    private Miniscript.Context<Integer> context(List<byte[]> publicKeys) {
        return new Miniscript.Context<>() {
            public boolean taproot(){return taprootScript;}
            public Integer fromPublicKey(byte[] key){throw new UnsupportedOperationException();}
            public Integer fromKeyHash(byte[] key){throw new UnsupportedOperationException();}
            public byte[] publicKey(Integer key){byte[] bytes=publicKeys.get(key);return taprootScript?xonly(bytes):bytes;}
            public String keyText(Integer key){return keys.get(key).text();}
        };
    }

    static Parsed parse(String encoded,boolean requireChecksum,NetworkParameters network) {
        return parse(encoded,requireChecksum,network,Map.of());
    }
    static Parsed parse(String encoded,boolean requireChecksum,NetworkParameters network,Map<String,PrivateKey> existing) {
        String[] parts=encoded.split("#",-1);
        if(parts.length>2)throw invalid("Multiple '#' symbols");
        if(parts.length==1&&requireChecksum)throw invalid("Missing checksum");
        if(parts.length==2&&parts[1].length()!=8)throw invalid("Expected 8 character checksum, not "+parts[1].length()+" characters");
        String checksum;try{checksum=DescriptorChecksum.add(parts[0]);checksum=checksum.substring(checksum.length()-8);}catch(IllegalArgumentException bad){throw invalid("Invalid characters in payload");}
        if(parts.length==2&&!parts[1].equals(checksum))throw invalid("Provided checksum '"+parts[1]+"' does not match computed checksum '"+checksum+"'");
        var parser=new Parser(network);parser.available.putAll(existing);var descriptors=parser.parse(parts[0],Context.TOP);
        return new Parsed(descriptors,checksum,Map.copyOf(parser.available));
    }
    private static RpcException invalid(String message){return new RpcException(-5,message);}
    private static final class Parser {
        final NetworkParameters network;final Map<String,PrivateKey> available=new HashMap<>();
        Parser(NetworkParameters network){this.network=network;}
        List<OutputDescriptor> parse(String input,Context context) {
            var cursor=new Cursor(input);String expression=cursor.expression();
            List<OutputDescriptor> result=parseExpression(expression,context);if(cursor.remaining())throw invalid("");return result;
        }
        private List<OutputDescriptor> parseExpression(String expression,Context context) {
            for(String name:List.of("pk","pkh","combo","multi","sortedmulti","multi_a","sortedmulti_a","wpkh","sh","wsh","addr","tr","rawtr","unused","raw")) {
                if(!expression.startsWith(name+"(")||!expression.endsWith(")"))continue;
                String body=expression.substring(name.length()+1,expression.length()-1);Kind kind=Kind.valueOf(name.toUpperCase(Locale.ROOT));
                switch(kind) {
                    case PK,PKH,WPKH,COMBO,RAWTR,UNUSED->{
                        if(kind==Kind.PKH&&context==Context.P2TR)break;
                        if(kind==Kind.COMBO&&context!=Context.TOP)throw invalid("Can only have combo() at top level");
                        if(kind==Kind.WPKH&&context!=Context.TOP&&context!=Context.P2SH)throw invalid("Can only have wpkh() at top level or inside sh()");
                        if(kind==Kind.RAWTR&&context!=Context.TOP)throw invalid("Can only have rawtr at top level");
                        if(kind==Kind.UNUSED&&context!=Context.TOP)throw invalid("Can only have unused at top level");
                        if(kind==Kind.RAWTR||kind==Kind.UNUSED){var argument=new Cursor(body);body=argument.expression();if(argument.remaining())throw invalid(kind==Kind.RAWTR?"rawtr(): only one key expected.":"unused(): only one key expected");}
                        Context keyContext=kind==Kind.WPKH?Context.P2WPKH:kind==Kind.RAWTR?Context.P2TR:context;
                        List<DescriptorKey> keys=keys(body,keyContext,kind==Kind.UNUSED?"":name+"(): ");var result=new ArrayList<OutputDescriptor>();
                        for(var key:keys){if(kind==Kind.UNUSED&&key.ranged())throw invalid("unused(): key cannot be ranged");result.add(node(kind,List.of(key),List.of(),0,null,null,null,List.of(),context==Context.P2TR));}return result;
                    }
                    case SH,WSH->{
                        if(kind==Kind.SH&&context!=Context.TOP)throw invalid("Can only have sh() at top level");
                        if(kind==Kind.WSH&&context!=Context.TOP&&context!=Context.P2SH)throw invalid("Can only have wsh() at top level or inside sh()");
                        var children=parse(body,kind==Kind.SH?Context.P2SH:Context.P2WSH);return children.stream().map(child->node(kind,List.of(),List.of(child),0,null,null,null,List.of(),false)).toList();
                    }
                    case MULTI,SORTEDMULTI,MULTI_A,SORTEDMULTI_A-> {
                        boolean multiA=kind==Kind.MULTI_A||kind==Kind.SORTEDMULTI_A;
                        if(multiA&&context!=Context.P2TR)throw invalid("Can only have multi_a/sortedmulti_a inside tr()");
                        if(!multiA&&context==Context.P2TR)throw invalid("Can only have multi/sortedmulti at top level, in sh(), or in wsh()");
                        var cursor=new Cursor(body);String threshold=cursor.expression();long k=unsigned(threshold);
                        if(k<0)throw invalid("Multi threshold '"+threshold+"' is not valid");
                        var providers=new ArrayList<List<DescriptorKey>>();long size=0;
                        while(cursor.remaining()){if(!cursor.consume(','))throw invalid("Multi: expected ',', got '"+cursor.peek()+"'");var key=keys(cursor.expression(),context,"Multi: ");providers.add(key);size+=key.getFirst().size()+1;}
                        if(providers.isEmpty()||providers.size()>(multiA?999:20))throw invalid("Cannot have "+providers.size()+" keys in "+(multiA?"multi_a":"multisig")+"; must have between 1 and "+(multiA?999:20)+" keys, inclusive");
                        if(k<1)throw invalid("Multisig threshold cannot be "+k+", must be at least 1");
                        if(k>providers.size())throw invalid("Multisig threshold cannot be larger than the number of keys; threshold is "+k+" but only "+providers.size()+" keys specified");
                        if(context==Context.TOP&&providers.size()>3)throw invalid("Cannot have "+providers.size()+" pubkeys in bare multisig; only at most 3 pubkeys");
                        if(context==Context.P2SH&&size+3>520)throw invalid("P2SH script is too large, "+(size+3)+" bytes is larger than 520 bytes");
                        int count=aligned(providers,"multi(): Multipath derivation paths have mismatched lengths");var result=new ArrayList<OutputDescriptor>();
                        for(int branch=0;branch<count;branch++)result.add(node(kind,branchKeys(providers,branch),List.of(),k,null,null,null,List.of(),multiA));return result;
                    }
                    case ADDR,RAW->{
                        if(context!=Context.TOP)throw invalid("Can only have "+name+"() at top level");
                        byte[] script;String address=null;
                        if(kind==Kind.RAW){try{if(body.isEmpty()||(body.length()&1)!=0)throw new IllegalArgumentException();script=HexFormat.of().parseHex(body);}catch(IllegalArgumentException bad){throw invalid("Raw script is not hex");}}
                        else{try{script=BitcoinAddressDecoder.toScriptPubKey(body,network);address=(String)ScriptJson.output(script,network).get("address");}catch(IllegalArgumentException bad){throw invalid("Address is not valid");}}
                        return List.of(node(kind,List.of(),List.of(),0,script,address,null,List.of(),false));
                    }
                    case TR->{if(context!=Context.TOP)throw invalid("Can only have tr at top level");return taproot(body);}
                    default->throw new IllegalStateException();
                }
            }
            return miniscript(expression,context);
        }
        private List<OutputDescriptor> miniscript(String expression,Context context) {
            boolean taproot=context!=Context.P2WSH;var providers=new ArrayList<List<DescriptorKey>>();
            var parsed=MiniscriptText.parse(expression,taproot,key->{var provider=keys(key,taproot?Context.P2TR:Context.P2WSH,"");int index=providers.size();providers.add(provider);return index;});
            if(parsed!=null) {
                if(context!=Context.P2WSH&&context!=Context.P2TR)throw invalid("Miniscript expressions can only be used in wsh or tr.");
                var limits=MiniscriptLimits.analyze(parsed,(a,b)->{
                    var keyA=providers.get(a).getFirst();var keyB=providers.get(b).getFirst();var pubA=keyA.derive(0,available);var pubB=keyB.derive(0,available);
                    if(pubA.isPresent()&&pubB.isPresent())return Arrays.compareUnsigned(pubA.get().publicKey(),pubB.get().publicKey());
                    if(pubA.isPresent()!=pubB.isPresent())return pubA.isPresent()?1:-1;return keyA.text().compareTo(keyB.text());
                });
                if(!limits.sane()||!limits.satisfiable()) {
                    var bad=limits.firstInsane()==null?parsed:limits.firstInsane();var presentation=node(Kind.MINISCRIPT,branchKeys(providers,0),List.of(),0,null,null,bad,List.of(),taproot);
                    String error=MiniscriptEncoding.text(bad,presentation.context(null));
                    if(!bad.valid())error+=" is invalid";
                    else if(!limits.sane()) {
                        error+=" is not sane";String reason=limits.firstInsaneReason();
                        if(MiniscriptTypes.has(bad.type,"m")&&bad==parsed&&!MiniscriptTypes.has(bad.type,"s"))reason="witnesses without signature exist";
                        if(reason!=null)error+=": "+reason;
                    } else error+=" is not satisfiable";
                    throw invalid(error);
                }
                int count=aligned(providers,"Miniscript: Multipath derivation paths have mismatched lengths");var result=new ArrayList<OutputDescriptor>();
                for(int branch=0;branch<count;branch++)result.add(node(Kind.MINISCRIPT,branchKeys(providers,branch),List.of(),0,null,null,parsed,List.of(),taproot));return result;
            }
            throw invalid(context==Context.P2SH?"A function is needed within P2SH":context==Context.P2WSH?"A function is needed within P2WSH":"'"+expression+"' is not a valid descriptor function");
        }
        private List<OutputDescriptor> taproot(String body) {
            var cursor=new Cursor(body);var internal=keys(cursor.expression(),Context.P2TR,"tr(): ");
            var leaves=new ArrayList<List<OutputDescriptor>>();var depths=new ArrayList<Integer>();
            if(cursor.remaining()) {
                if(!cursor.consume(','))throw invalid("tr: expected ',', got '"+cursor.peek()+"'");var branches=new ArrayList<Boolean>();
                do {
                    while(cursor.consume('{')){branches.add(false);if(branches.size()>128)throw invalid("tr() supports at most 128 nesting levels");}
                    leaves.add(parse(cursor.expression(),Context.P2TR));depths.add(branches.size());
                    while(!branches.isEmpty()&&branches.getLast()){if(!cursor.consume('}'))throw invalid("tr(): expected '}' after script expression");branches.removeLast();}
                    if(!branches.isEmpty()&&!branches.getLast()){if(!cursor.consume(','))throw invalid("tr(): expected ',' after script expression");branches.set(branches.size()-1,true);}
                }while(!branches.isEmpty());
                if(cursor.remaining())throw invalid("tr(): expected ')' after script expression");
            }
            int count=internal.size();for(var leaf:leaves)count=Math.max(count,leaf.size());
            for(var leaf:leaves)if(leaf.size()!=1&&leaf.size()!=count)throw invalid("tr(): Multipath subscripts have mismatched lengths");
            if(internal.size()>1&&internal.size()!=count)throw invalid("tr(): Multipath internal key mismatches multipath subscripts lengths");
            var result=new ArrayList<OutputDescriptor>();
            for(int branch=0;branch<count;branch++){var scripts=new ArrayList<OutputDescriptor>();for(var leaf:leaves)scripts.add(leaf.get(leaf.size()==1?0:branch));result.add(node(Kind.TR,List.of(internal.get(internal.size()==1?0:branch)),scripts,0,null,null,null,depths,false));}return result;
        }
        private List<DescriptorKey> keys(String expression,Context context,String prefix) {
            try{var keys=DescriptorKey.parse(expression,context,network);keys.forEach(key->key.collectPrivate(available));return keys;}
            catch(DescriptorKey.Invalid invalid){throw OutputDescriptor.invalid(prefix+invalid.getMessage());}
        }
        private static int aligned(List<? extends List<?>> providers,String error) {
            int count=1;for(var provider:providers)count=Math.max(count,provider.size());for(var provider:providers)if(provider.size()!=1&&provider.size()!=count)throw invalid(error);return count;
        }
        private static List<DescriptorKey> branchKeys(List<List<DescriptorKey>> providers,int branch) {var keys=new ArrayList<DescriptorKey>();for(var provider:providers)keys.add(provider.get(provider.size()==1?0:branch));return keys;}
    }
    private static final class Cursor {
        final String input;int position;
        Cursor(String input){this.input=input;}
        boolean remaining(){return position<input.length();}
        char peek(){return remaining()?input.charAt(position):0;}
        boolean consume(char c){if(peek()!=c)return false;position++;return true;}
        String expression(){int start=position,depth=0;while(remaining()){char c=peek();if(c=='('||c=='{')depth++;else if(depth!=0&&(c==')'||c=='}'))depth--;else if(depth==0&&(c==')'||c=='}'||c==','))break;position++;}return input.substring(start,position);}
    }
    private static OutputDescriptor node(Kind kind,List<DescriptorKey> keys,List<OutputDescriptor> children,long threshold,byte[] raw,String address,Miniscript.Node<Integer> mini,List<Integer> depths,boolean taproot) {return new OutputDescriptor(kind,keys,children,threshold,raw,address,mini,depths,taproot);}
    private static long unsigned(String value) {try{if(value.isEmpty()||!value.chars().allMatch(c->c>='0'&&c<='9'))return -1;long result=Long.parseLong(value);return result<=0xffff_ffffL?result:-1;}catch(NumberFormatException bad){return -1;}}
    private static byte[] pkh(byte[] key){return join(new byte[]{0x76,(byte)0xa9,20},Hash160.hash(key),new byte[]{(byte)0x88,(byte)0xac});}
    private static byte[] sh(byte[] script){return join(new byte[]{(byte)0xa9,20},Hash160.hash(script),new byte[]{(byte)0x87});}
    private static byte[] witness(int version,byte[] program){return join(new byte[]{(byte)(version==0?0:0x50+version),(byte)program.length},program);}
    private static byte[] xonly(byte[] publicKey){return Arrays.copyOfRange(publicKey,1,33);}
    private static byte[] tapBranch(byte[] a,byte[] b){return Arrays.compareUnsigned(a,b)<0?TaggedHash.hash("TapBranch",a,b):TaggedHash.hash("TapBranch",b,a);}
    private static byte[] join(byte[]... parts){var result=new ByteArrayOutputStream();for(byte[] part:parts)result.writeBytes(part);return result.toByteArray();}
}

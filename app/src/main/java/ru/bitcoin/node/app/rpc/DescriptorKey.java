package ru.bitcoin.node.app.rpc;

// Descriptor key semantics: Copyright (c) 2017-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.address.*;
import ru.bitcoin.node.protocol.network.*;
import java.util.*;

/** Descriptor key provider with origins, private/public BIP32, multipath and MuSig2. */
final class DescriptorKey {
    enum Context { TOP,P2SH,P2WSH,P2WPKH,P2TR,MUSIG }
    static final class Invalid extends RuntimeException { Invalid(String message){super(message);} }
    record Derived(byte[] publicKey,PrivateKey privateKey,byte[] fingerprint,List<Long> path,byte[] aggregate,List<Derived> participants,boolean explicitOrigin) {
        Derived { publicKey=publicKey.clone();fingerprint=fingerprint.clone();path=List.copyOf(path);aggregate=aggregate==null?null:aggregate.clone();participants=List.copyOf(participants); }
    }
    private static final HexFormat HEX=HexFormat.of();
    private static final byte[] MUSIG_CHAIN=HEX.parseHex("868087ca02a6f974c4598924c36b57762d32cb45717167e300622c7167e38965");
    private final byte[] fixed;
    private final PrivateKey secret;
    private final Bip32Key extended;
    private final List<DescriptorKey> participants;
    private final List<Long> path,originPath;
    private final byte[] origin;
    private final int range;
    private final boolean xonlyText,apostrophe,mainnet;

    private DescriptorKey(byte[] fixed,PrivateKey secret,Bip32Key extended,List<DescriptorKey> participants,
                          List<Long> path,int range,boolean xonlyText,boolean apostrophe,byte[] origin,List<Long> originPath,boolean mainnet) {
        this.fixed=fixed;this.secret=secret;this.extended=extended;this.participants=List.copyOf(participants);
        this.path=List.copyOf(path);this.range=range;this.xonlyText=xonlyText;this.apostrophe=apostrophe;
        this.origin=origin;this.originPath=List.copyOf(originPath);this.mainnet=mainnet;
    }
    boolean ranged(){return range!=0||participants.stream().anyMatch(DescriptorKey::ranged);}
    boolean bip32(){return extended!=null;}
    boolean hasPrivate(){return secret!=null||extended!=null&&extended.privateKey().isPresent()||participants.stream().anyMatch(DescriptorKey::hasPrivate);}
    int size(){return participants.isEmpty()?(fixed==null?33:fixed.length):32;}
    String text() {
        String base;
        if(extended!=null)base=extended.neuter().encode();
        else if(!participants.isEmpty())base="musig("+String.join(",",participants.stream().map(DescriptorKey::text).toList())+")";
        else base=HEX.formatHex(xonlyText?Arrays.copyOfRange(fixed,1,33):fixed);
        base+=format(path,participants.isEmpty()&&apostrophe);
        if(range!=0)base+="/*"+(range==2?(apostrophe?"'":"h"):"");
        return origin==null?base:"["+HEX.formatHex(origin)+format(originPath,apostrophe)+"]"+base;
    }
    Optional<Derived> derive(long position) {
        var available=new HashMap<String,PrivateKey>();collectPrivate(available);return derive(position,available);
    }
    void collectPrivate(Map<String,PrivateKey> available) {
        if(secret!=null)available.putIfAbsent(HEX.formatHex(Hash160.hash(fixed)),secret);
        if(extended!=null)extended.privateKey().ifPresent(key->available.putIfAbsent(HEX.formatHex(Hash160.hash(extended.publicKey())),key));
        for(var participant:participants)participant.collectPrivate(available);
    }
    Optional<Derived> derive(long position,Map<String,PrivateKey> available) {
        if(position<0||position>0x7fff_ffffL)return Optional.empty();
        byte[] pub=fixed;PrivateKey privateKey=secret;byte[] fingerprint;byte[] aggregate=null;List<Derived> derivedParticipants=List.of();
        Bip32Key key=extended;
        if(!participants.isEmpty()) {
            var parts=new ArrayList<Derived>();for(var participant:participants){var derived=participant.derive(position,available);if(derived.isEmpty())return Optional.empty();parts.add(derived.get());}
            parts.sort((a,b)->Arrays.compareUnsigned(a.publicKey,b.publicKey));derivedParticipants=parts;
            aggregate=MuSig2Aggregation.publicKey(parts.stream().map(Derived::publicKey).toList());if(aggregate==null)return Optional.empty();
            pub=aggregate;privateKey=null;
            if(!path.isEmpty()||range!=0)key=Bip32Key.publicRoot(aggregate,MUSIG_CHAIN,mainnet);
        }
        if(key!=null&&key.privateKey().isEmpty()) {
            var known=available.get(HEX.formatHex(Hash160.hash(key.publicKey())));if(known!=null)key=key.withPrivateKey(known);
        }
        if(key==null&&privateKey==null) {
            privateKey=available.get(HEX.formatHex(Hash160.hash(pub)));
            if(privateKey==null&&xonlyText){byte[] opposite=pub.clone();opposite[0]=(byte)(opposite[0]==2?3:2);privateKey=available.get(HEX.formatHex(Hash160.hash(opposite)));}
        }
        fingerprint=key==null?Arrays.copyOf(Hash160.hash(pub),4):key.fingerprint();
        var derivedPath=new ArrayList<>(path);if(range!=0)derivedPath.add(position+(range==2?0x8000_0000L:0));
        if(key!=null){for(long child:derivedPath){var next=key.derive(child);if(next.isEmpty())return Optional.empty();key=next.get();}pub=key.publicKey();privateKey=key.privateKey().orElse(null);}
        if(origin!=null){fingerprint=origin;var prefixed=new ArrayList<>(originPath);prefixed.addAll(derivedPath);derivedPath=prefixed;}
        return Optional.of(new Derived(pub,privateKey,fingerprint,derivedPath,aggregate,derivedParticipants,origin!=null));
    }

    static List<DescriptorKey> parse(String expression,Context context,NetworkParameters network) {
        boolean main=network.network()==BitcoinNetwork.MAINNET;
        if(expression.startsWith("musig("))return musig(expression,context,network);
        String[] originParts=expression.split("]",-1);if(originParts.length>2)throw new Invalid("Multiple ']' characters found for a single pubkey");
        var style=new Style();byte[] origin=null;List<Long> originPath=List.of();String keyExpression=expression;
        if(originParts.length==2) {
            String prefix=originParts[0];if(!prefix.startsWith("["))throw new Invalid("Key origin start '[ character expected but not found, got '"+(prefix.isEmpty()?']':prefix.charAt(0))+"' instead");
            String[] parts=prefix.substring(1).split("/",-1);
            if(parts[0].length()!=8)throw new Invalid("Fingerprint is not 4 bytes ("+parts[0].length()+" characters instead of 8 characters)");
            try{origin=HEX.parseHex(parts[0]);}catch(IllegalArgumentException invalid){throw new Invalid("Fingerprint '"+parts[0]+"' is not hex");}
            originPath=paths(Arrays.asList(parts).subList(1,parts.length),false,style).getFirst();keyExpression=originParts[1];
        }
        String[] split=keyExpression.split("/",-1);String base=split[0];
        if(base.isEmpty())throw new Invalid("No key provided");
        if(space(base.charAt(0))||space(base.charAt(base.length()-1)))throw new Invalid("Key '"+base+"' is invalid due to whitespace");
        boolean permitUncompressed=context==Context.TOP||context==Context.P2SH;
        if(split.length==1) {
            if(!base.isEmpty()&&(base.length()&1)==0&&base.chars().allMatch(DescriptorKey::hex)) {
                byte[] pub=HEX.parseHex(base);boolean xonly=pub.length==32&&context==Context.P2TR;
                if(pub.length==65&&(pub[0]==6||pub[0]==7))throw new Invalid("Hybrid public keys are not allowed");
                if(xonly){byte[] full=new byte[33];full[0]=2;System.arraycopy(pub,0,full,1,32);pub=full;}
                try{PublicKey.fromBytes(pub);}catch(IllegalArgumentException invalid){throw new Invalid("Pubkey '"+base+"' is invalid");}
                if(pub.length==65&&!permitUncompressed)throw new Invalid("Uncompressed keys are not allowed");
                return List.of(new DescriptorKey(pub,null,null,List.of(),List.of(),0,xonly,style.apostrophe,origin,originPath,main));
            }
            byte[] payload=null;
            try {
                payload=Base58Check.decode(base,34);int prefix=main?128:239;
                if((payload.length==33||payload.length==34)&&(payload[0]&255)==prefix&&(payload.length!=34||payload[33]==1)) {
                    PrivateKey secret=PrivateKey.fromBytes(Arrays.copyOfRange(payload,1,33));boolean compressed=payload.length==34;
                    if(!compressed&&!permitUncompressed)throw new Invalid("Uncompressed keys are not allowed");
                    byte[] pub=compressed?Secp256k1.publicKey(secret).compressed():Secp256k1.DOMAIN.getG().multiply(secret.value()).normalize().getEncoded(false);
                    return List.of(new DescriptorKey(pub,secret,null,List.of(),List.of(),0,context==Context.P2TR,style.apostrophe,origin,originPath,main));
                }
            } catch(IllegalArgumentException invalid) { /* It may be an extended key. */ }
            finally{if(payload!=null)Arrays.fill(payload,(byte)0);}
        }
        Bip32Key extended;try{extended=Bip32Key.decode(base,network);}catch(IllegalArgumentException invalid){throw new Invalid("key '"+base+"' is not valid");}
        var suffix=new ArrayList<>(Arrays.asList(split).subList(1,split.length));int range=range(suffix,style);
        var paths=paths(suffix,true,style);var result=new ArrayList<DescriptorKey>();
        for(var path:paths)result.add(new DescriptorKey(null,null,extended,List.of(),path,range,false,style.apostrophe,origin,originPath,main));
        return result;
    }

    private static List<DescriptorKey> musig(String expression,Context context,NetworkParameters network) {
        if(context!=Context.P2TR)throw new Invalid("musig() is only allowed in tr() and rawtr()");
        int close=expression.indexOf(')');if(close<0)throw new Invalid("Invalid musig() expression");
        if(expression.indexOf(')',close+1)>=0)throw new Invalid("Too many ')' in musig() expression");
        String inside=expression.substring(6,close);if(inside.isEmpty())throw new Invalid("musig(): Must contain key expressions");
        var participants=new ArrayList<List<DescriptorKey>>();int maximum=1;boolean anyRanged=false,allBip32=true;
        for(String part:inside.split(",",-1)) {
            List<DescriptorKey> parsed;try{parsed=parse(part,Context.MUSIG,network);}catch(Invalid invalid){throw new Invalid("musig(): "+invalid.getMessage());}
            participants.add(parsed);maximum=Math.max(maximum,parsed.size());anyRanged|=parsed.getFirst().ranged();allBip32&=parsed.getFirst().bip32();
        }
        String suffix=expression.substring(close+1);var style=new Style();int range=0;List<List<Long>> paths=List.of(List.of());
        if(suffix.startsWith("/")) {
            if(!allBip32)throw new Invalid("musig(): derivation requires all participants to be xpubs or xprvs");
            if(anyRanged)throw new Invalid("musig(): Cannot have ranged participant keys if musig() also has derivation");
            var elements=new ArrayList<>(Arrays.asList(suffix.substring(1).split("/",-1)));range=range(elements,style);
            if(range==2)throw new Invalid("musig(): Cannot have hardened child derivation");
            try{paths=paths(elements,true,style);}catch(Invalid invalid){throw new Invalid("musig(): "+invalid.getMessage());}
            if(paths.stream().flatMap(Collection::stream).anyMatch(child->child>=0x8000_0000L))throw new Invalid("musig(): cannot have hardened derivation steps");
        }
        if(maximum>1&&paths.size()>1)throw new Invalid("musig(): Cannot have multipath participant keys if musig() is also multipath");
        for(var participant:participants)if(participant.size()!=1&&participant.size()!=maximum)throw new Invalid("musig(): Multipath derivation paths have mismatched lengths");
        var result=new ArrayList<DescriptorKey>();int count=Math.max(maximum,paths.size());
        for(int index=0;index<count;index++) {
            var branch=new ArrayList<DescriptorKey>();for(var participant:participants)branch.add(participant.get(participant.size()==1?0:index));
            result.add(new DescriptorKey(null,null,null,branch,paths.get(paths.size()==1?0:index),range,false,false,null,List.of(),network.network()==BitcoinNetwork.MAINNET));
        }
        return result;
    }
    private static final class Style { boolean apostrophe; }
    private static int range(List<String> path,Style style) {
        if(path.isEmpty())return 0;String last=path.getLast();int range=last.equals("*")?1:last.equals("*h")||last.equals("*'")?2:0;
        if(range!=0){path.removeLast();if(range==2)style.apostrophe=last.endsWith("'");}return range;
    }
    private static List<List<Long>> paths(List<String> elements,boolean multipath,Style style) {
        var path=new ArrayList<Long>();List<Long> replacements=null;int replacementIndex=-1;
        for(String element:elements) {
            if(element.startsWith("<")&&element.endsWith(">")) {
                if(!multipath)throw new Invalid("Key path value '"+element+"' specifies multipath in a section where multipath is not allowed");
                if(replacements!=null)throw new Invalid("Multiple multipath key path specifiers found");
                String[] choices=element.substring(1,element.length()-1).split(";",-1);
                if(choices.length<2)throw new Invalid("Multipath key path specifiers must have at least two items");
                replacements=new ArrayList<>();var seen=new HashSet<Long>();
                for(String choice:choices){long child=child(choice,style);if(!seen.add(child))throw new Invalid("Duplicated key path value "+child+" in multipath specifier");replacements.add(child);}
                replacementIndex=path.size();path.add(0L);
            } else path.add(child(element,style));
        }
        if(replacements==null)return List.of(path);
        var result=new ArrayList<List<Long>>();for(long replacement:replacements){var branch=new ArrayList<>(path);branch.set(replacementIndex,replacement);result.add(branch);}return result;
    }
    private static long child(String expression,Style style) {
        if(expression.isEmpty())throw new Invalid("Key path value '' is not valid");
        boolean hardened=expression.endsWith("h")||expression.endsWith("'");String number=hardened?expression.substring(0,expression.length()-1):expression;
        long child;
        try{if(number.isEmpty()||!number.chars().allMatch(c->c>='0'&&c<='9'))throw new NumberFormatException();child=Long.parseLong(number);if(child>0xffff_ffffL)throw new NumberFormatException();}
        catch(NumberFormatException invalid){throw new Invalid("Key path value '"+expression+"' is not a valid uint32");}
        if(child>=0x8000_0000L)throw new Invalid("Key path value "+child+" is out of range");
        if(hardened){style.apostrophe=expression.endsWith("'");child+=0x8000_0000L;}return child;
    }
    private static String format(List<Long> path,boolean apostrophe) {var result=new StringBuilder();for(long child:path)result.append('/').append(child&0x7fff_ffffL).append(child>=0x8000_0000L?(apostrophe?"'":"h"):"");return result.toString();}
    private static boolean hex(int c){return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F';}
    private static boolean space(char c){return c==' '||c>='\t'&&c<='\r';}
}

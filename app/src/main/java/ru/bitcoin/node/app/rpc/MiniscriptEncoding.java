package ru.bitcoin.node.app.rpc;

// Miniscript encodings: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.crypto.hash.Hash160;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.*;
import static ru.bitcoin.node.script.Opcode.*;

/** Iterative, linear-output Script/text encoders, including VERIFY folding and syntactic sugar. */
final class MiniscriptEncoding {
    private MiniscriptEncoding() { }
    private record ScriptTask<K>(Miniscript.Node<K> node,boolean verify) { }
    private record TextTask<K>(Miniscript.Node<K> node,boolean wrapped) { }

    static <K> byte[] script(Miniscript.Node<K> root,Miniscript.Context<K> context) {
        var output=new ByteArrayOutputStream(root.scriptSize);var tasks=new ArrayDeque<Object>();tasks.push(new ScriptTask<>(root,false));
        while(!tasks.isEmpty()) {
            Object task=tasks.pop();
            if(task instanceof byte[] bytes){output.writeBytes(bytes);continue;}
            if(task instanceof Integer opcode){output.write(opcode);continue;}
            @SuppressWarnings("unchecked") var current=(ScriptTask<K>)task;
            var node=current.node;boolean verify=current.verify;var children=node.children;
            var parts=new ArrayList<Object>();
            switch(node.fragment) {
                case PK_K->parts.add(push(context.publicKey(node.keys.getFirst())));
                case PK_H->{parts.add(OP_DUP);parts.add(OP_HASH160);parts.add(push(Hash160.hash(context.publicKey(node.keys.getFirst()))));parts.add(OP_EQUALVERIFY);}
                case AFTER,OLDER->{parts.add(number(node.k));parts.add(node.fragment==Fragment.AFTER?OP_CHECKLOCKTIMEVERIFY:OP_CHECKSEQUENCEVERIFY);}
                case SHA256,RIPEMD160,HASH160,HASH256->{
                    parts.add(OP_SIZE);parts.add(number(32));parts.add(OP_EQUALVERIFY);
                    parts.add(switch(node.fragment){case SHA256->OP_SHA256;case RIPEMD160->OP_RIPEMD160;case HASH160->OP_HASH160;default->OP_HASH256;});
                    parts.add(push(node.data));parts.add(verify?OP_EQUALVERIFY:OP_EQUAL);
                }
                case JUST_0,JUST_1->parts.add(node.fragment==Fragment.JUST_0?OP_0:OP_1);
                case WRAP_A->{parts.add(OP_TOALTSTACK);parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_FROMALTSTACK);}
                case WRAP_S->{parts.add(OP_SWAP);parts.add(new ScriptTask<>(children.getFirst(),verify));}
                case WRAP_C->{parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(verify?OP_CHECKSIGVERIFY:OP_CHECKSIG);}
                case WRAP_D->{parts.add(OP_DUP);parts.add(OP_IF);parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_ENDIF);}
                case WRAP_V->{parts.add(new ScriptTask<>(children.getFirst(),true));if(has(children.getFirst().type,"x"))parts.add(OP_VERIFY);}
                case WRAP_J->{parts.add(OP_SIZE);parts.add(OP_0NOTEQUAL);parts.add(OP_IF);parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_ENDIF);}
                case WRAP_N->{parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_0NOTEQUAL);}
                case AND_V->{parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(new ScriptTask<>(children.get(1),verify));}
                case AND_B,OR_B->{parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(new ScriptTask<>(children.get(1),false));parts.add(node.fragment==Fragment.AND_B?OP_BOOLAND:OP_BOOLOR);}
                case OR_D,OR_C->{parts.add(new ScriptTask<>(children.getFirst(),false));if(node.fragment==Fragment.OR_D)parts.add(OP_IFDUP);parts.add(OP_NOTIF);parts.add(new ScriptTask<>(children.get(1),false));parts.add(OP_ENDIF);}
                case OR_I->{parts.add(OP_IF);parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_ELSE);parts.add(new ScriptTask<>(children.get(1),false));parts.add(OP_ENDIF);}
                case ANDOR->{parts.add(new ScriptTask<>(children.getFirst(),false));parts.add(OP_NOTIF);parts.add(new ScriptTask<>(children.get(2),false));parts.add(OP_ELSE);parts.add(new ScriptTask<>(children.get(1),false));parts.add(OP_ENDIF);}
                case MULTI->{parts.add(number(node.k));for(K key:node.keys)parts.add(push(context.publicKey(key)));parts.add(number(node.keys.size()));parts.add(verify?OP_CHECKMULTISIGVERIFY:OP_CHECKMULTISIG);}
                case MULTI_A->{for(int i=0;i<node.keys.size();i++){parts.add(push(context.publicKey(node.keys.get(i))));parts.add(i==0?OP_CHECKSIG:0xba);}parts.add(number(node.k));parts.add(verify?OP_NUMEQUALVERIFY:OP_NUMEQUAL);}
                case THRESH->{for(int i=0;i<children.size();i++){parts.add(new ScriptTask<>(children.get(i),false));if(i>0)parts.add(OP_ADD);}parts.add(number(node.k));parts.add(verify?OP_EQUALVERIFY:OP_EQUAL);}
            }
            for(int i=parts.size()-1;i>=0;i--)tasks.push(parts.get(i));
        }
        return output.toByteArray();
    }

    static <K> String text(Miniscript.Node<K> root,Miniscript.Context<K> context) {
        var output=new StringBuilder();var tasks=new ArrayDeque<Object>();tasks.push(new TextTask<>(root,false));
        while(!tasks.isEmpty()) {
            Object task=tasks.pop();if(task instanceof String literal){output.append(literal);continue;}
            @SuppressWarnings("unchecked") var current=(TextTask<K>)task;
            var node=current.node;var children=node.children;String prefix=current.wrapped?":":"";
            var parts=new ArrayList<Object>();
            switch(node.fragment) {
                case WRAP_A,WRAP_S,WRAP_D,WRAP_V,WRAP_J,WRAP_N->{parts.add(switch(node.fragment){case WRAP_A->"a";case WRAP_S->"s";case WRAP_D->"d";case WRAP_V->"v";case WRAP_J->"j";default->"n";});parts.add(new TextTask<>(children.getFirst(),true));}
                case WRAP_C->{
                    var child=children.getFirst();
                    if(child.fragment==Fragment.PK_K||child.fragment==Fragment.PK_H)parts.add(prefix+(child.fragment==Fragment.PK_K?"pk(":"pkh(")+context.keyText(child.keys.getFirst())+")");
                    else{parts.add("c");parts.add(new TextTask<>(child,true));}
                }
                case AND_V->{
                    if(children.get(1).fragment==Fragment.JUST_1){parts.add("t");parts.add(new TextTask<>(children.getFirst(),true));}
                    else call(parts,prefix+"and_v",children);
                }
                case OR_I->{
                    if(children.getFirst().fragment==Fragment.JUST_0){parts.add("l");parts.add(new TextTask<>(children.get(1),true));}
                    else if(children.get(1).fragment==Fragment.JUST_0){parts.add("u");parts.add(new TextTask<>(children.getFirst(),true));}
                    else call(parts,prefix+"or_i",children);
                }
                case ANDOR->{if(children.get(2).fragment==Fragment.JUST_0)call(parts,prefix+"and_n",children.subList(0,2));else call(parts,prefix+"andor",children);}
                case AND_B,OR_B,OR_D,OR_C->call(parts,prefix+node.fragment.name().toLowerCase(Locale.ROOT),children);
                case PK_K,PK_H->parts.add(prefix+node.fragment.name().toLowerCase(Locale.ROOT)+"("+context.keyText(node.keys.getFirst())+")");
                case AFTER,OLDER->parts.add(prefix+node.fragment.name().toLowerCase(Locale.ROOT)+"("+node.k+")");
                case SHA256,RIPEMD160,HASH160,HASH256->parts.add(prefix+node.fragment.name().toLowerCase(Locale.ROOT)+"("+HexFormat.of().formatHex(node.data)+")");
                case JUST_0,JUST_1->parts.add(prefix+(node.fragment==Fragment.JUST_0?"0":"1"));
                case MULTI,MULTI_A->{parts.add(prefix+node.fragment.name().toLowerCase(Locale.ROOT)+"("+node.k);for(K key:node.keys)parts.add(","+context.keyText(key));parts.add(")");}
                case THRESH->{parts.add(prefix+"thresh("+node.k);for(var child:children){parts.add(",");parts.add(new TextTask<>(child,false));}parts.add(")");}
            }
            for(int i=parts.size()-1;i>=0;i--)tasks.push(parts.get(i));
        }
        return output.toString();
    }
    private static <K> void call(List<Object> parts,String name,List<Miniscript.Node<K>> children) {
        parts.add(name+"(");for(int i=0;i<children.size();i++){if(i!=0)parts.add(",");parts.add(new TextTask<>(children.get(i),false));}parts.add(")");
    }
    static byte[] number(long value) {
        if(value==0)return new byte[]{0};if(value<=16)return new byte[]{(byte)(0x50+value)};
        var bytes=new ByteArrayOutputStream();while(value!=0){bytes.write((int)value&255);value>>>=8;}
        byte[] data=bytes.toByteArray();if((data[data.length-1]&128)!=0)data=Arrays.copyOf(data,data.length+1);return push(data);
    }
    static byte[] push(byte[] value) {
        var output=new ByteArrayOutputStream();
        if(value.length<76)output.write(value.length);
        else if(value.length<=255){output.write(0x4c);output.write(value.length);}
        else if(value.length<=65535){output.write(0x4d);output.write(value.length&255);output.write(value.length>>>8);}
        else{output.write(0x4e);for(int i=0;i<4;i++)output.write(value.length>>>(8*i)&255);}
        output.writeBytes(value);return output.toByteArray();
    }
}

package ru.bitcoin.node.app.rpc;

// Miniscript parsing: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt. Based on Core v32.1 script/miniscript.h.

import ru.bitcoin.node.script.*;
import java.util.*;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.*;
import static ru.bitcoin.node.script.Opcode.*;

/** Iterative typed Miniscript decoder shared by descriptor inference and satisfaction. */
final class Miniscript {
    private Miniscript() { }
    interface Context<K> {
        boolean taproot();
        K fromPublicKey(byte[] key);
        K fromKeyHash(byte[] hash);
        byte[] publicKey(K key);
        String keyText(K key);
    }
    static final class Node<K> {
        final Fragment fragment;final List<Node<K>> children;final List<K> keys;final byte[] data;final long k;
        final int type,scriptSize;final boolean taproot;
        Node(Fragment fragment,List<Node<K>> children,List<K> keys,byte[] data,long k,boolean taproot){
            this.fragment=fragment;this.children=List.copyOf(children);this.keys=List.copyOf(keys);this.data=data.clone();this.k=k;this.taproot=taproot;
            type=compute(fragment,children.stream().mapToInt(child->child.type).toArray(),k,taproot);
            int size=0;for(Node<K> child:children)size+=child.scriptSize;
            scriptSize=switch(fragment){
                case JUST_0,JUST_1->1;case PK_K->taproot?33:34;case PK_H->24;case OLDER,AFTER->1+numberSize(k);
                case SHA256,HASH256->39;case RIPEMD160,HASH160->27;
                case MULTI->1+numberSize(keys.size())+numberSize(k)+34*keys.size();case MULTI_A->34*keys.size()+numberSize(k)+1;
                case AND_V->size;case WRAP_V->size+(has(children.getFirst().type,"x")?1:0);
                case WRAP_S,WRAP_C,WRAP_N,AND_B,OR_B->size+1;case WRAP_A,OR_C->size+2;
                case WRAP_D,OR_D,OR_I,ANDOR->size+3;case WRAP_J->size+4;case THRESH->size+children.size()+numberSize(k);
            };
        }
        boolean valid(){return type!=0&&scriptSize<=maximumScriptSize(taproot);}
        boolean validTopLevel(){return valid()&&has(type,"B");}
    }
    private enum State { SINGLE,BKV,W,SWAP,ALT,CHECK,DUP_IF,VERIFY,NON_ZERO,ZERO_NOTEQUAL,MAYBE_AND_V,AND_V,AND_B,ANDOR,OR_B,OR_C,OR_D,THRESH_W,THRESH_E,ENDIF,ENDIF_NOTIF,ENDIF_ELSE }
    private record Step(State state,int n,long k){Step(State state){this(state,-1,-1);}}
    private record Op(int code,byte[] data){ }

    static int maximumScriptSize(boolean taproot){return taproot?329482:3600;}
    static int numberSize(long value){if(value<=16)return 1;int bytes=(64-Long.numberOfLeadingZeros(value)+7)/8;if((value>>>(8*(bytes-1))&0x80)!=0)bytes++;return bytes+1;}

    static <K> Node<K> parseScript(byte[] script,Context<K> context){
        if(script.length>maximumScriptSize(context.taproot()))return null;
        List<Op> ops=decompose(script);if(ops==null)return null;
        var pending=new ArrayDeque<Step>();pending.push(new Step(State.BKV));var nodes=new ArrayList<Node<K>>();int position=0;
        while(!pending.isEmpty()){
            if(!nodes.isEmpty()&&!nodes.getLast().valid())return null;
            Step step=pending.pop();int remaining=ops.size()-position;int code=remaining>0?ops.get(position).code():-1;
            switch(step.state()){
                case SINGLE->{
                    if(remaining==0)return null;
                    if(code==OP_1||code==OP_0){nodes.add(node(code==OP_1?Fragment.JUST_1:Fragment.JUST_0,context));position++;break;}
                    byte[] data=ops.get(position).data();
                    if(data.length==33||data.length==32){K key=context.fromPublicKey(data);if(key==null)return null;nodes.add(keyNode(Fragment.PK_K,List.of(key),0,context));position++;break;}
                    if(remaining>=5&&code==OP_VERIFY&&ops.get(position+1).code()==OP_EQUAL&&ops.get(position+3).code()==OP_HASH160&&ops.get(position+4).code()==OP_DUP&&ops.get(position+2).data().length==20){
                        K key=context.fromKeyHash(ops.get(position+2).data());if(key==null)return null;nodes.add(keyNode(Fragment.PK_H,List.of(key),0,context));position+=5;break;
                    }
                    Long number=remaining>=2?number(ops.get(position+1)):null;
                    if(remaining>=2&&(code==OP_CHECKLOCKTIMEVERIFY||code==OP_CHECKSEQUENCEVERIFY)&&number!=null){
                        if(number<1||number>0x7fff_ffffL)return null;nodes.add(new Node<>(code==OP_CHECKLOCKTIMEVERIFY?Fragment.AFTER:Fragment.OLDER,List.of(),List.of(),new byte[0],number,context.taproot()));position+=2;break;
                    }
                    if(remaining>=7&&code==OP_EQUAL&&ops.get(position+3).code()==OP_VERIFY&&ops.get(position+4).code()==OP_EQUAL&&Objects.equals(number(ops.get(position+5)),32L)&&ops.get(position+6).code()==OP_SIZE){
                        int hashCode=ops.get(position+2).code(),length=ops.get(position+1).data().length;
                        Fragment fragment=hashCode==OP_SHA256&&length==32?Fragment.SHA256:hashCode==OP_RIPEMD160&&length==20?Fragment.RIPEMD160:hashCode==OP_HASH256&&length==32?Fragment.HASH256:hashCode==OP_HASH160&&length==20?Fragment.HASH160:null;
                        if(fragment!=null){nodes.add(new Node<>(fragment,List.of(),List.of(),ops.get(position+1).data(),0,context.taproot()));position+=7;break;}
                    }
                    if(remaining>=3&&code==OP_CHECKMULTISIG){
                        if(context.taproot()||number==null||number<1||number>20||remaining<3+number)return null;
                        int count=number.intValue();var keys=new ArrayList<K>();for(int index=0;index<count;index++){byte[] encoded=ops.get(position+2+index).data();if(encoded.length!=33)return null;K key=context.fromPublicKey(encoded);if(key==null)return null;keys.add(key);}
                        Long required=number(ops.get(position+2+count));if(required==null||required<1||required>count)return null;
                        Collections.reverse(keys);nodes.add(keyNode(Fragment.MULTI,keys,required,context));position+=3+count;break;
                    }
                    if(remaining>=4&&code==OP_NUMEQUAL){
                        if(!context.taproot()||number==null||number<1||number>999||remaining<2+number*2)return null;
                        var keys=new ArrayList<K>();int offset=2;
                        while(true){if(remaining<offset+2)return null;int check=ops.get(position+offset).code();byte[] bytes=ops.get(position+offset+1).data();
                            if(check!=0xba&&check!=OP_CHECKSIG||bytes.length!=32)return null;K key=context.fromPublicKey(bytes);if(key==null)return null;keys.add(key);if(keys.size()>999)return null;if(check==OP_CHECKSIG)break;offset+=2;}
                        if(keys.size()<number)return null;position+=2+keys.size()*2;Collections.reverse(keys);nodes.add(keyNode(Fragment.MULTI_A,keys,number,context));break;
                    }
                    if(code==OP_CHECKSIG||code==OP_VERIFY||code==OP_0NOTEQUAL){
                        position++;pending.push(new Step(code==OP_CHECKSIG?State.CHECK:code==OP_VERIFY?State.VERIFY:State.ZERO_NOTEQUAL));pending.push(new Step(State.SINGLE));break;
                    }
                    if(remaining>=3&&code==OP_EQUAL&&number!=null){if(number<1)return null;position+=2;pending.push(new Step(State.THRESH_W,0,number));break;}
                    if(code==OP_ENDIF){position++;pending.push(new Step(State.ENDIF));pending.push(new Step(State.BKV));break;}
                    if(code==OP_BOOLAND||code==OP_BOOLOR){position++;pending.push(new Step(code==OP_BOOLAND?State.AND_B:State.OR_B));pending.push(new Step(State.SINGLE));pending.push(new Step(State.W));break;}
                    return null;
                }
                case BKV->{pending.push(new Step(State.MAYBE_AND_V));pending.push(new Step(State.SINGLE));}
                case W->{if(remaining==0)return null;if(code==OP_FROMALTSTACK){position++;pending.push(new Step(State.ALT));}else pending.push(new Step(State.SWAP));pending.push(new Step(State.BKV));}
                case MAYBE_AND_V->{if(remaining>0&&code!=OP_IF&&code!=OP_ELSE&&code!=OP_NOTIF&&code!=OP_TOALTSTACK&&code!=OP_SWAP){pending.push(new Step(State.AND_V));pending.push(new Step(State.BKV));}}
                case SWAP,ALT->{if(remaining==0||code!=(step.state()==State.SWAP?OP_SWAP:OP_TOALTSTACK)||nodes.isEmpty())return null;position++;wrap(nodes,step.state()==State.SWAP?Fragment.WRAP_S:Fragment.WRAP_A,context);}
                case CHECK,DUP_IF,VERIFY,NON_ZERO,ZERO_NOTEQUAL->{if(nodes.isEmpty())return null;wrap(nodes,switch(step.state()){case CHECK->Fragment.WRAP_C;case DUP_IF->Fragment.WRAP_D;case VERIFY->Fragment.WRAP_V;case NON_ZERO->Fragment.WRAP_J;default->Fragment.WRAP_N;},context);}
                case AND_V,AND_B,OR_B,OR_C,OR_D->{if(nodes.size()<2)return null;binary(nodes,switch(step.state()){case AND_V->Fragment.AND_V;case AND_B->Fragment.AND_B;case OR_B->Fragment.OR_B;case OR_C->Fragment.OR_C;default->Fragment.OR_D;},context);}
                case ANDOR->{if(nodes.size()<3)return null;Node<K> left=nodes.removeLast(),right=nodes.removeLast(),middle=nodes.removeLast();nodes.add(branch(Fragment.ANDOR,List.of(left,middle,right),0,context));}
                case THRESH_W->{if(remaining==0)return null;if(code==OP_ADD){position++;pending.push(new Step(State.THRESH_W,step.n()+1,step.k()));pending.push(new Step(State.W));}
                    else{pending.push(new Step(State.THRESH_E,step.n()+1,step.k()));pending.push(new Step(State.SINGLE));}}
                case THRESH_E->{if(step.k()<1||step.k()>step.n()||nodes.size()<step.n())return null;var children=new ArrayList<Node<K>>();for(int index=0;index<step.n();index++)children.add(nodes.removeLast());nodes.add(branch(Fragment.THRESH,children,step.k(),context));}
                case ENDIF->{
                    if(remaining==0)return null;
                    if(code==OP_ELSE){position++;pending.push(new Step(State.ENDIF_ELSE));pending.push(new Step(State.BKV));}
                    else if(code==OP_IF){if(remaining>=2&&ops.get(position+1).code()==OP_DUP){position+=2;pending.push(new Step(State.DUP_IF));}
                        else if(remaining>=3&&ops.get(position+1).code()==OP_0NOTEQUAL&&ops.get(position+2).code()==OP_SIZE){position+=3;pending.push(new Step(State.NON_ZERO));}else return null;}
                    else if(code==OP_NOTIF){position++;pending.push(new Step(State.ENDIF_NOTIF));}else return null;
                }
                case ENDIF_NOTIF->{if(remaining==0)return null;if(code==OP_IFDUP){position++;pending.push(new Step(State.OR_D));}else pending.push(new Step(State.OR_C));pending.push(new Step(State.SINGLE));}
                case ENDIF_ELSE->{if(remaining==0)return null;if(code==OP_IF){if(nodes.size()<2)return null;position++;binary(nodes,Fragment.OR_I,context);}
                    else if(code==OP_NOTIF){position++;pending.push(new Step(State.ANDOR));pending.push(new Step(State.SINGLE));}else return null;}
            }
        }
        if(position!=ops.size()||nodes.size()!=1||!nodes.getFirst().validTopLevel())return null;
        return nodes.getFirst();
    }

    private static <K> Node<K> node(Fragment fragment,Context<K> context){return new Node<>(fragment,List.of(),List.of(),new byte[0],0,context.taproot());}
    private static <K> Node<K> keyNode(Fragment fragment,List<K> keys,long k,Context<K> context){return new Node<>(fragment,List.of(),keys,new byte[0],k,context.taproot());}
    private static <K> Node<K> branch(Fragment fragment,List<Node<K>> children,long k,Context<K> context){return new Node<>(fragment,children,List.of(),new byte[0],k,context.taproot());}
    private static <K> void wrap(ArrayList<Node<K>> nodes,Fragment fragment,Context<K> context){nodes.add(branch(fragment,List.of(nodes.removeLast()),0,context));}
    private static <K> void binary(ArrayList<Node<K>> nodes,Fragment fragment,Context<K> context){Node<K> left=nodes.removeLast(),right=nodes.removeLast();nodes.add(branch(fragment,List.of(left,right),0,context));}

    private static List<Op> decompose(byte[] script){
        List<ScriptInstruction> instructions;try{instructions=ScriptParser.parse(script);}catch(ScriptParseException malformed){return null;}
        var result=new ArrayList<Op>();for(int index=0;index<instructions.size();index++){
            ScriptInstruction instruction=instructions.get(index);int code=instruction.opcode();byte[] data=instruction.data();if(data==null)data=new byte[0];
            if(code>=OP_1&&code<=OP_16)data=new byte[]{(byte)(code-OP_1+1)};
            else if(code==OP_CHECKSIGVERIFY||code==OP_CHECKMULTISIGVERIFY||code==OP_EQUALVERIFY||code==OP_NUMEQUALVERIFY){
                result.add(new Op(code==OP_CHECKSIGVERIFY?OP_CHECKSIG:code==OP_CHECKMULTISIGVERIFY?OP_CHECKMULTISIG:code==OP_EQUALVERIFY?OP_EQUAL:OP_NUMEQUAL,new byte[0]));code=OP_VERIFY;
            }else if(code<=OP_PUSHDATA4){if(!minimal(code,data))return null;}
            else if((code==OP_CHECKSIG||code==OP_CHECKMULTISIG||code==OP_EQUAL||code==OP_NUMEQUAL)&&index+1<instructions.size()&&instructions.get(index+1).opcode()==OP_VERIFY)return null;
            result.add(new Op(code,data));
        }
        Collections.reverse(result);return result;
    }
    private static boolean minimal(int code,byte[] data){if(data.length==0)return code==OP_0;if(data.length==1&&data[0]>=1&&data[0]<=16)return code==OP_1+data[0]-1;if(data.length==1&&(data[0]&255)==0x81)return code==OP_1NEGATE;
        return data.length<=75?code==data.length:data.length<=255?code==OP_PUSHDATA1:data.length<=65535?code==OP_PUSHDATA2:code==OP_PUSHDATA4;}
    private static Long number(Op instruction){if(instruction.code()==OP_0)return 0L;byte[] data=instruction.data();if(data.length==0||data.length>4||instruction.code()<=OP_PUSHDATA4&&!minimal(instruction.code(),data))return null;
        if((data[data.length-1]&0x7f)==0&&(data.length==1||(data[data.length-2]&0x80)==0))return null;
        long value=0;for(int i=0;i<data.length;i++)value|=(long)(data[i]&255)<<(8*i);return (data[data.length-1]&0x80)==0?value:-(value&~(0x80L<<(8*(data.length-1))));}
}

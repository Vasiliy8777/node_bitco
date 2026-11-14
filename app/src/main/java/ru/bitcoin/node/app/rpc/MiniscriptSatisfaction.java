package ru.bitcoin.node.app.rpc;

// Miniscript satisfaction: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt. Port of Core v32.1 InputStack/ProduceInput.

import java.util.*;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.Fragment;

/** Nonrecursive satisfaction/dissatisfaction with Core's malleability and cost ordering. */
final class MiniscriptSatisfaction {
    private MiniscriptSatisfaction() { }
    enum Availability { NO,MAYBE,YES }
    record Item(Availability availability,byte[] bytes){Item{bytes=bytes.clone();}}
    interface Context<K> extends Miniscript.Context<K> {
        Item sign(K key);
        Item preimage(Fragment fragment,byte[] hash);
        boolean checkOlder(long lock);
        boolean checkAfter(long lock);
    }
    private static final class Sequence {
        final byte[] item;final Sequence left,right;final int count;
        Sequence(byte[] item){this.item=item;left=null;right=null;count=1;}
        Sequence(Sequence left,Sequence right){item=null;this.left=left;this.right=right;count=left.count+right.count;}
    }
    static final class Plan {
        final Sequence sequence;final Availability availability;final boolean hasSignature,malleable,nonCanonical;final long size;
        Plan(Sequence sequence,Availability availability,boolean signature,boolean malleable,boolean nonCanonical,long size){
            this.sequence=sequence;this.availability=availability;hasSignature=signature;this.malleable=malleable;this.nonCanonical=nonCanonical;this.size=size;
        }
        Plan malleable(boolean value){return new Plan(sequence,availability,hasSignature,value,nonCanonical,size);}
        Plan nonCanonical(){return new Plan(sequence,availability,hasSignature,malleable,true,size);}
        List<byte[]> items(){
            if(sequence==null)return List.of();var result=new ArrayList<byte[]>(sequence.count);var pending=new ArrayDeque<Sequence>();pending.push(sequence);
            while(!pending.isEmpty()){Sequence next=pending.pop();if(next.item!=null)result.add(next.item.clone());else{pending.push(next.right);pending.push(next.left);}}
            return result;
        }
    }
    record Result(Plan dissatisfaction,Plan satisfaction) { }
    private static final Plan INVALID=new Plan(null,Availability.NO,false,false,false,Long.MAX_VALUE);
    private static final Plan EMPTY=new Plan(null,Availability.YES,false,false,false,0);
    private static final Plan ZERO=item(new byte[0]);
    private static final Plan ONE=item(new byte[]{1});
    private static final Plan ZERO32=item(new byte[32]).malleable(true);
    private static Plan item(byte[] bytes){return new Plan(new Sequence(bytes.clone()),Availability.YES,false,false,false,bytes.length+1L);}
    private static Plan supplied(Item item,boolean signature){return item.availability()==Availability.NO?INVALID:new Plan(new Sequence(item.bytes().clone()),item.availability(),signature,false,false,item.bytes().length+1L);}
    private static Plan add(Plan first,Plan second){
        if(first.availability==Availability.NO||second.availability==Availability.NO)return INVALID;
        Sequence sequence=first.sequence==null?second.sequence:second.sequence==null?first.sequence:new Sequence(first.sequence,second.sequence);
        return new Plan(sequence,first.availability==Availability.MAYBE||second.availability==Availability.MAYBE?Availability.MAYBE:Availability.YES,
                first.hasSignature||second.hasSignature,first.malleable||second.malleable,first.nonCanonical||second.nonCanonical,first.size+second.size);
    }
    private static Plan choose(Plan first,Plan second){
        if(first.availability==Availability.NO)return second;if(second.availability==Availability.NO)return first;
        if(!first.hasSignature&&second.hasSignature)return first;if(!second.hasSignature&&first.hasSignature)return second;
        if(!first.hasSignature){first=first.malleable(true);second=second.malleable(true);}
        else{if(second.malleable&&!first.malleable)return first;if(first.malleable&&!second.malleable)return second;}
        if(first.availability==Availability.YES&&second.availability==Availability.YES)return first.size<=second.size?first:second;
        if(first.availability==Availability.MAYBE&&second.availability==Availability.MAYBE)return first.size>=second.size?first:second;
        return first.availability==Availability.YES?first:second;
    }

    static <K> Plan satisfy(Miniscript.Node<K> root,Context<K> context){Plan plan=produce(root,context).satisfaction();return plan.malleable||!plan.hasSignature?INVALID:plan;}

    static <K> Result produce(Miniscript.Node<K> root,Context<K> context){
        record Frame<T>(Miniscript.Node<T> node,boolean visited){}
        var pending=new ArrayDeque<Frame<K>>();var results=new IdentityHashMap<Miniscript.Node<K>,Result>();pending.push(new Frame<>(root,false));
        while(!pending.isEmpty()){
            Frame<K> frame=pending.pop();Miniscript.Node<K> node=frame.node();
            if(!frame.visited()){
                if(results.containsKey(node))continue;pending.push(new Frame<>(node,true));for(int index=node.children.size()-1;index>=0;index--)pending.push(new Frame<>(node.children.get(index),false));continue;
            }
            var children=node.children.stream().map(results::get).toList();Result result=evaluate(node,children,context);results.put(node,result);
        }
        return results.get(root);
    }

    private static <K> Result evaluate(Miniscript.Node<K> node,List<Result> children,Context<K> context){
        Result x=children.isEmpty()?null:children.getFirst(),y=children.size()>1?children.get(1):null,z=children.size()>2?children.get(2):null;
        return switch(node.fragment){
            case PK_K->new Result(ZERO,supplied(context.sign(node.keys.getFirst()),true));
            case PK_H->{Plan key=item(context.publicKey(node.keys.getFirst()));yield new Result(add(ZERO,key),add(supplied(context.sign(node.keys.getFirst()),true),key));}
            case MULTI,MULTI_A->{
                boolean tapscript=node.fragment==Fragment.MULTI_A;var sats=new ArrayList<Plan>();sats.add(tapscript?EMPTY:ZERO);
                for(int index=0;index<node.keys.size();index++){
                    K key=node.keys.get(tapscript?node.keys.size()-1-index:index);Plan signature=supplied(context.sign(key),true);var next=new ArrayList<Plan>();
                    next.add(tapscript?add(sats.getFirst(),ZERO):sats.getFirst());
                    for(int count=1;count<sats.size();count++)next.add(choose(tapscript?add(sats.get(count),ZERO):sats.get(count),add(sats.get(count-1),signature)));
                    next.add(add(sats.getLast(),signature));sats=next;
                }
                Plan dissatisfaction=sats.getFirst();if(!tapscript){dissatisfaction=ZERO;for(int i=0;i<node.k;i++)dissatisfaction=add(dissatisfaction,ZERO);}
                yield new Result(dissatisfaction,sats.get((int)node.k));
            }
            case THRESH->{
                var sats=new ArrayList<Plan>();sats.add(EMPTY);
                for(int index=children.size()-1;index>=0;index--){
                    Result child=children.get(index);var next=new ArrayList<Plan>();next.add(add(sats.getFirst(),child.dissatisfaction()));
                    for(int count=1;count<sats.size();count++)next.add(choose(add(sats.get(count),child.dissatisfaction()),add(sats.get(count-1),child.satisfaction())));
                    next.add(add(sats.getLast(),child.satisfaction()));sats=next;
                }
                Plan dissatisfaction=INVALID;
                for(int count=0;count<sats.size();count++)if(count!=node.k){Plan plan=sats.get(count);if(count!=0)plan=plan.malleable(true).nonCanonical();dissatisfaction=choose(dissatisfaction,plan);}
                yield new Result(dissatisfaction,sats.get((int)node.k));
            }
            case OLDER->new Result(INVALID,context.checkOlder(node.k)?EMPTY:INVALID);
            case AFTER->new Result(INVALID,context.checkAfter(node.k)?EMPTY:INVALID);
            case SHA256,RIPEMD160,HASH256,HASH160->new Result(ZERO32,supplied(context.preimage(node.fragment,node.data),false));
            case AND_V->new Result(add(y.dissatisfaction(),x.satisfaction()).nonCanonical(),add(y.satisfaction(),x.satisfaction()));
            case AND_B->new Result(choose(choose(add(y.dissatisfaction(),x.dissatisfaction()),add(y.satisfaction(),x.dissatisfaction()).malleable(true).nonCanonical()),
                    add(y.dissatisfaction(),x.satisfaction()).malleable(true).nonCanonical()),add(y.satisfaction(),x.satisfaction()));
            case OR_B->new Result(add(y.dissatisfaction(),x.dissatisfaction()),choose(choose(add(y.dissatisfaction(),x.satisfaction()),add(y.satisfaction(),x.dissatisfaction())),add(y.satisfaction(),x.satisfaction()).malleable(true).nonCanonical()));
            case OR_C->new Result(INVALID,choose(x.satisfaction(),add(y.satisfaction(),x.dissatisfaction())));
            case OR_D->new Result(add(y.dissatisfaction(),x.dissatisfaction()),choose(x.satisfaction(),add(y.satisfaction(),x.dissatisfaction())));
            case OR_I->new Result(choose(add(x.dissatisfaction(),ONE),add(y.dissatisfaction(),ZERO)),choose(add(x.satisfaction(),ONE),add(y.satisfaction(),ZERO)));
            case ANDOR->new Result(choose(add(y.dissatisfaction(),x.satisfaction()).nonCanonical(),add(z.dissatisfaction(),x.dissatisfaction())),choose(add(y.satisfaction(),x.satisfaction()),add(z.satisfaction(),x.dissatisfaction())));
            case WRAP_A,WRAP_S,WRAP_C,WRAP_N->x;
            case WRAP_D->new Result(ZERO,add(x.satisfaction(),ONE));
            case WRAP_J->new Result(ZERO.malleable(x.dissatisfaction().availability!=Availability.NO&&!x.dissatisfaction().hasSignature),x.satisfaction());
            case WRAP_V->new Result(INVALID,x.satisfaction());
            case JUST_0->new Result(EMPTY,INVALID);
            case JUST_1->new Result(INVALID,EMPTY);
        };
    }
}

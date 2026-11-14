package ru.bitcoin.node.app.rpc;

// Miniscript resource/sanity analysis: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import java.util.*;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.*;

/** Worst-case canonical satisfaction limits; alternatives form a max/concatenation semiring. */
final class MiniscriptLimits {
    private record Trace(int net,int peak) { }
    private static final Trace EMPTY=new Trace(0,0),PUSH=new Trace(-1,0),POP=new Trace(1,1),EQUAL_VERIFY=new Trace(2,2);
    record Result<K>(boolean sane,boolean satisfiable,Miniscript.Node<K> firstInsane,Integer maxOps,Integer initialStack,Integer executionStack,String firstInsaneReason) { }
    private record Visit<K>(Miniscript.Node<K> node,boolean ready) { }
    private record Info<K>(int count,int satOps,int dsatOps,Trace sat,Trace dsat,Set<K> keys,boolean duplicate,
                           boolean saneSub,Miniscript.Node<K> firstInsane,String reason) { }

    static <K> Result<K> analyze(Miniscript.Node<K> root,Comparator<K> keyComparator) {
        var pending=new ArrayDeque<Visit<K>>();var values=new ArrayList<Info<K>>();pending.push(new Visit<>(root,false));
        while(!pending.isEmpty()) {
            var visit=pending.pop();var node=visit.node;
            if(!visit.ready){pending.push(new Visit<>(node,true));for(int i=node.children.size()-1;i>=0;i--)pending.push(new Visit<>(node.children.get(i),false));continue;}
            int start=values.size()-node.children.size();var children=new ArrayList<>(values.subList(start,values.size()));values.subList(start,values.size()).clear();
            Info<K> x=children.size()>0?children.get(0):null,y=children.size()>1?children.get(1):null,z=children.size()>2?children.get(2):null;
            int count=0,satOps=0,dsatOps=0;Trace sat=null,dsat=null;
            switch(node.fragment) {
                case JUST_0->{satOps=-1;dsat=PUSH;}
                case JUST_1->{dsatOps=-1;sat=PUSH;}
                case PK_K->{sat=PUSH;dsat=PUSH;}
                case PK_H->{count=3;sat=concat(PUSH,EMPTY,PUSH,EQUAL_VERIFY);dsat=sat;}
                case OLDER,AFTER->{count=1;dsatOps=-1;sat=PUSH;}
                case SHA256,RIPEMD160,HASH256,HASH160->{count=4;dsatOps=-1;sat=concat(PUSH,PUSH,EQUAL_VERIFY,EMPTY,PUSH,POP);}
                case MULTI->{count=1;satOps=node.keys.size();dsatOps=satOps;sat=new Trace((int)node.k,(int)node.k+node.keys.size()+2);dsat=sat;}
                case MULTI_A->{count=node.keys.size()+1;sat=new Trace(node.keys.size()-1,node.keys.size());dsat=sat;}
                case AND_V->{count=x.count+y.count;satOps=add(x.satOps,y.satOps);dsatOps=-1;sat=concat(x.sat,y.sat);}
                case AND_B->{count=1+x.count+y.count;satOps=add(x.satOps,y.satOps);dsatOps=add(x.dsatOps,y.dsatOps);sat=concat(x.sat,y.sat,POP);dsat=concat(x.dsat,y.dsat,POP);}
                case OR_B->{count=1+x.count+y.count;satOps=max(add(x.satOps,y.dsatOps),add(y.satOps,x.dsatOps));dsatOps=add(x.dsatOps,y.dsatOps);sat=concat(union(concat(x.sat,y.dsat),concat(x.dsat,y.sat)),POP);dsat=concat(x.dsat,y.dsat,POP);}
                case OR_C->{count=2+x.count+y.count;satOps=max(x.satOps,add(y.satOps,x.dsatOps));dsatOps=-1;sat=union(concat(x.sat,POP),concat(x.dsat,POP,y.sat));}
                case OR_D->{count=3+x.count+y.count;satOps=max(x.satOps,add(y.satOps,x.dsatOps));dsatOps=add(x.dsatOps,y.dsatOps);sat=union(concat(x.sat,PUSH,POP),concat(x.dsat,EMPTY,POP,y.sat));dsat=concat(x.dsat,EMPTY,POP,y.dsat);}
                case OR_I->{count=3+x.count+y.count;satOps=max(x.satOps,y.satOps);dsatOps=max(x.dsatOps,y.dsatOps);sat=concat(POP,union(x.sat,y.sat));dsat=concat(POP,union(x.dsat,y.dsat));}
                case ANDOR->{count=3+x.count+y.count+z.count;satOps=max(add(y.satOps,x.satOps),add(x.dsatOps,z.satOps));dsatOps=add(x.dsatOps,z.dsatOps);sat=union(concat(x.sat,POP,y.sat),concat(x.dsat,POP,z.sat));dsat=concat(x.dsat,POP,z.dsat);}
                case WRAP_S,WRAP_C,WRAP_N,WRAP_A->{
                    count=x.count+(node.fragment==Fragment.WRAP_A?2:1);satOps=x.satOps;dsatOps=x.dsatOps;sat=x.sat;dsat=x.dsat;
                    if(node.fragment==Fragment.WRAP_C){sat=concat(sat,POP);dsat=concat(dsat,POP);}
                }
                case WRAP_D,WRAP_J->{count=x.count+(node.fragment==Fragment.WRAP_D?3:4);satOps=x.satOps;dsatOps=0;sat=concat(PUSH,POP,x.sat);dsat=concat(PUSH,POP);}
                case WRAP_V->{count=x.count+(has(node.children.getFirst().type,"x")?1:0);satOps=x.satOps;dsatOps=-1;sat=concat(x.sat,POP);}
                case THRESH->{
                    int[] ops={0};Trace[] traces={EMPTY};
                    for(int i=0;i<children.size();i++) {
                        var child=children.get(i);count+=child.count+1;
                        int size=ops.length;int[] nextOps=new int[size+1];Trace[] nextTraces=new Trace[size+1];
                        Arrays.fill(nextOps,-1);Trace addition=i==0?EMPTY:POP;
                        for(int j=0;j<=size;j++) {
                            int a=j<size?add(ops[j],child.dsatOps):-1,b=j>0?add(ops[j-1],child.satOps):-1;nextOps[j]=max(a,b);
                            Trace ta=j<size?concat(traces[j],child.dsat):null,tb=j>0?concat(traces[j-1],child.sat):null;nextTraces[j]=concat(union(ta,tb),addition);
                        }
                        ops=nextOps;traces=nextTraces;
                    }
                    satOps=ops[(int)node.k];dsatOps=ops[0];sat=concat(traces[(int)node.k],PUSH,POP);dsat=concat(traces[0],PUSH,POP);
                }
            }
            boolean duplicate=children.stream().anyMatch(Info::duplicate);Set<K> keys=null;
            if(!duplicate) {
                keys=new TreeSet<>(keyComparator);for(K key:node.keys)if(!keys.add(key))duplicate=true;
                for(var child:children) {
                    if(duplicate)break;
                    if(keys.size()<child.keys.size()){Set<K> previous=keys;keys=child.keys;for(K key:previous)if(!keys.add(key))duplicate=true;}
                    else for(K key:child.keys)if(!keys.add(key))duplicate=true;
                }
            }
            int offset=(node.type&mask("BKW"))==0?0:1;
            boolean limits=node.valid()&&(node.taproot||satOps<0||count+satOps<=201)
                    &&(sat==null||(node.taproot?sat.peak+offset<=1000:sat.net+offset<=100));
            boolean saneSub=limits&&has(node.type,"m")&&has(node.type,"k")&&!duplicate;
            String reason=!has(node.type,"m")?"malleable witnesses exist":!has(node.type,"k")?"contains mixes of timelocks expressed in blocks and seconds":duplicate?"contains duplicate public keys":!limits?"needs witnesses that may exceed resource limits":null;
            Miniscript.Node<K> bad=null;for(var child:children)if(child.firstInsane!=null){bad=child.firstInsane;reason=child.reason;break;}
            if(bad==null&&!saneSub)bad=node;
            values.add(new Info<>(count,satOps,dsatOps,sat,dsat,keys,duplicate,saneSub,bad,reason));
        }
        var info=values.getFirst();int offset=(root.type&mask("BKW"))==0?0:1;
        return new Result<>(root.validTopLevel()&&info.saneSub&&has(root.type,"s"),info.sat!=null,info.firstInsane,
                info.satOps<0?null:info.count+info.satOps,info.sat==null?null:info.sat.net+offset,info.sat==null?null:info.sat.peak+offset,info.reason);
    }
    private static int add(int a,int b) { return a<0||b<0?-1:a+b; }
    private static int max(int a,int b) { return Math.max(a,b); }
    private static Trace union(Trace a,Trace b) { if(a==null)return b;if(b==null)return a;return new Trace(Math.max(a.net,b.net),Math.max(a.peak,b.peak)); }
    private static Trace concat(Trace... traces) { Trace value=EMPTY;for(Trace next:traces){if(next==null)return null;value=new Trace(value.net+next.net,Math.max(next.peak,next.net+value.peak));}return value; }
}

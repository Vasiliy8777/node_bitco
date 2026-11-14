package ru.bitcoin.node.app.rpc;

// Miniscript grammar: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import java.util.*;
import java.util.function.Function;
import static ru.bitcoin.node.app.rpc.MiniscriptTypes.Fragment;

/** Non-recursive text decoder. Key expressions are delegated to the descriptor key parser. */
final class MiniscriptText {
    private MiniscriptText() { }
    static <K> Miniscript.Node<K> parse(String expression,boolean taproot,Function<String,K> keys) {
        try { return new Parser<>(expression,taproot,keys).parse(); }
        catch(Invalid invalid) { return null; }
    }
    private static final class Invalid extends RuntimeException { }
    private static final class Frame<K> {
        final Fragment fragment;final long k;final int arity;final boolean andN;final String wrappers;
        final List<Miniscript.Node<K>> children=new ArrayList<>();
        Frame(Fragment fragment,long k,int arity,boolean andN,String wrappers) {
            this.fragment=fragment;this.k=k;this.arity=arity;this.andN=andN;this.wrappers=wrappers;
        }
    }
    private static final class Parser<K> {
        private final String input;private final boolean taproot;private final Function<String,K> keys;
        private int position;
        private int minimumSize=1;
        Parser(String input,boolean taproot,Function<String,K> keys) { this.input=input;this.taproot=taproot;this.keys=keys; }
        Miniscript.Node<K> parse() {
            var frames=new ArrayDeque<Frame<K>>();
            while(true) {
                String wrappers=wrappers();accountWrappers(wrappers);Miniscript.Node<K> node;
                if(peek()=='0'||peek()=='1') {
                    char constant=take();node=node(constant=='0'?Fragment.JUST_0:Fragment.JUST_1,List.of(),List.of(),new byte[0],0);
                } else {
                    int functionStart=position;String name=identifier();expect('(');
                    switch(name) {
                        case "pk","pkh","pk_k","pk_h"->{
                            String keyText=functionBody(functionStart,name);K key=keys.apply(keyText);if(key==null)throw new Invalid();
                            account(name.equals("pk")?(taproot?33:34):name.equals("pk_k")?(taproot?32:33):name.equals("pkh")?24:23);node=node(name.equals("pk")||name.equals("pk_k")?Fragment.PK_K:Fragment.PK_H,List.of(),List.of(key),new byte[0],0);
                            if(name.equals("pk")||name.equals("pkh"))node=node(Fragment.WRAP_C,List.of(node),List.of(),new byte[0],0);
                        }
                        case "after","older"->{long k=number();if(k<1)throw new Invalid();expect(')');account(Miniscript.numberSize(k));node=node(name.equals("after")?Fragment.AFTER:Fragment.OLDER,List.of(),List.of(),new byte[0],k);}
                        case "sha256","hash256","hash160","ripemd160"->{
                            String hex=token();int size=name.equals("sha256")||name.equals("hash256")?64:40;
                            if(hex.length()!=size)throw new Invalid();byte[] hash;
                            try{hash=HexFormat.of().parseHex(hex);}catch(IllegalArgumentException invalid){throw new Invalid();}
                            expect(')');account(size==64?38:26);node=node(Fragment.valueOf(name.toUpperCase(Locale.ROOT)),List.of(),List.of(),hash,0);
                        }
                        case "multi","multi_a"->{
                            boolean multiA=name.equals("multi_a");if(multiA!=taproot)throw new Invalid();
                            long k=number();expect(',');var parsed=new ArrayList<K>();
                            while(true){parsed.add(key());if(parsed.size()>(multiA?999:20))throw new Invalid();if(peek()!=',')break;take();}
                            expect(')');if(k<1||k>parsed.size())throw new Invalid();account(multiA?34*parsed.size()+Miniscript.numberSize(k):2+(parsed.size()>16?1:0)+(k>16?1:0)+34*parsed.size());node=node(multiA?Fragment.MULTI_A:Fragment.MULTI,List.of(),parsed,new byte[0],k);
                        }
                        case "and_v","and_b","or_b","or_c","or_d","or_i","andor","and_n","thresh"->{
                            long k=0;int arity=name.equals("andor")?3:2;
                            if(name.equals("thresh")){k=number();if(k<1)throw new Invalid();expect(',');arity=-1;}
                            Fragment fragment=name.equals("and_n")?Fragment.ANDOR:Fragment.valueOf(name.toUpperCase(Locale.ROOT));
                            account(switch(name){case "and_v"->1;case "and_b","or_b"->2;case "or_c"->3;case "or_d","or_i"->4;case "andor","and_n"->5;default->Miniscript.numberSize(k)+1;});
                            frames.push(new Frame<>(fragment,k,arity,name.equals("and_n"),wrappers));continue;
                        }
                        default->throw new Invalid();
                    }
                }
                node=wrap(node,wrappers);
                while(true) {
                    if(frames.isEmpty()){if(position!=input.length())throw new Invalid();return node;}
                    Frame<K> frame=frames.peek();frame.children.add(node);
                    if(peek()==',') {
                        if(frame.arity!=-1&&frame.children.size()>=frame.arity)throw new Invalid();if(frame.arity==-1)account(2);take();break;
                    }
                    expect(')');frames.pop();
                    if(frame.arity!=-1&&frame.children.size()!=frame.arity||frame.arity==-1&&frame.k>frame.children.size())throw new Invalid();
                    if(frame.andN)frame.children.add(node(Fragment.JUST_0,List.of(),List.of(),new byte[0],0));
                    node=wrap(node(frame.fragment,frame.children,List.of(),new byte[0],frame.k),frame.wrappers);
                }
            }
        }
        private Miniscript.Node<K> wrap(Miniscript.Node<K> node,String wrappers) {
            for(int i=wrappers.length()-1;i>=0;i--) {
                char wrapper=wrappers.charAt(i);
                if(wrapper=='t')node=node(Fragment.AND_V,List.of(node,node(Fragment.JUST_1,List.of(),List.of(),new byte[0],0)),List.of(),new byte[0],0);
                else if(wrapper=='l')node=node(Fragment.OR_I,List.of(node(Fragment.JUST_0,List.of(),List.of(),new byte[0],0),node),List.of(),new byte[0],0);
                else if(wrapper=='u')node=node(Fragment.OR_I,List.of(node,node(Fragment.JUST_0,List.of(),List.of(),new byte[0],0)),List.of(),new byte[0],0);
                else {
                    Fragment fragment=switch(wrapper){case 'a'->Fragment.WRAP_A;case 's'->Fragment.WRAP_S;case 'c'->Fragment.WRAP_C;case 'd'->Fragment.WRAP_D;case 'v'->Fragment.WRAP_V;case 'j'->Fragment.WRAP_J;case 'n'->Fragment.WRAP_N;default->throw new Invalid();};
                    node=node(fragment,List.of(node),List.of(),new byte[0],0);
                }
            }
            return node;
        }
        private Miniscript.Node<K> node(Fragment fragment,List<Miniscript.Node<K>> children,List<K> keys,byte[] data,long k) {
            return new Miniscript.Node<>(fragment,children,keys,data,k,taproot);
        }
        private void account(int amount){minimumSize+=amount;if(minimumSize>Miniscript.maximumScriptSize(taproot))throw new Invalid();}
        private void accountWrappers(String wrappers){
            for(int i=0;i<wrappers.length();i++){
                char wrapper=wrappers.charAt(i);if(wrapper=='v'&&i>0&&wrappers.charAt(i-1)=='v')throw new Invalid();
                account(switch(wrapper){case 'v'->0;case 'a'->2;case 'd'->3;case 'j','l','u'->4;default->1;});
            }
        }
        private String functionBody(int start,String name){
            int end=start,depth=0;
            while(end<input.length()){
                char c=input.charAt(end);if(c=='('||c=='{')depth++;else if(depth>0&&(c==')'||c=='}'))depth--;else if(depth==0&&(c==')'||c=='}'||c==','))break;end++;
            }
            if(end-start<name.length()+2||input.charAt(end-1)!=')')throw new Invalid();
            position=end;return input.substring(start+name.length()+1,end-1);
        }
        private String wrappers() {
            int start=position;while("ascdvjntlu".indexOf(peek())>=0&&peek()!=0)position++;
            if(position>start&&peek()==':'){String result=input.substring(start,position);position++;return result;}
            position=start;return "";
        }
        private String identifier() {
            int start=position;while(peek()>='a'&&peek()<='z'||peek()>='0'&&peek()<='9'||peek()=='_')position++;
            if(start==position)throw new Invalid();return input.substring(start,position);
        }
        private long number() {
            long value=0;int start=position;
            while(peek()>='0'&&peek()<='9'){value=value*10+(take()-'0');if(value>0x7fff_ffffL)throw new Invalid();}
            if(position==start)throw new Invalid();return value;
        }
        private K key() { K key=keys.apply(token());if(key==null)throw new Invalid();return key; }
        private String token() {
            int start=position,depth=0;
            while(position<input.length()) {
                char c=peek();if(depth==0&&(c==','||c==')'))break;
                if(c=='(')depth++;else if(c==')')depth--;position++;
            }
            if(start==position||depth!=0)throw new Invalid();return input.substring(start,position);
        }
        private char peek() { return position<input.length()?input.charAt(position):0; }
        private char take() { if(position>=input.length())throw new Invalid();return input.charAt(position++); }
        private void expect(char wanted) { if(take()!=wanted)throw new Invalid(); }
    }
}

package ru.bitcoin.node.app.rpc;

// Miniscript type rules: Copyright (c) 2019-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt. Port of Core v32.1 script/miniscript.cpp.

/** The Miniscript type lattice; bit assignments are internal and are not wire flags. */
final class MiniscriptTypes {
    enum Fragment { PK_K,PK_H,OLDER,AFTER,SHA256,RIPEMD160,HASH256,HASH160,JUST_1,JUST_0,
        WRAP_A,WRAP_S,WRAP_C,WRAP_D,WRAP_V,WRAP_J,WRAP_N,AND_V,AND_B,OR_B,OR_D,OR_C,OR_I,ANDOR,MULTI,MULTI_A,THRESH }
    private static final String ALPHABET="BVKWzonduefsmxghijk";
    private MiniscriptTypes() { }
    static int mask(String flags){int value=0;for(int i=0;i<flags.length();i++){int bit=ALPHABET.indexOf(flags.charAt(i));if(bit<0)throw new IllegalArgumentException("Unknown Miniscript property");value|=1<<bit;}return value;}
    static boolean has(int value,String flags){int required=mask(flags);return (value&required)==required;}
    private static int iff(boolean condition,String flags){return condition?mask(flags):0;}
    private static int iff(boolean condition,int flags){return condition?flags:0;}
    private static boolean compatible(int x,int y){return !(has(x,"g")&&has(y,"h")||has(x,"h")&&has(y,"g")||has(x,"i")&&has(y,"j")||has(x,"j")&&has(y,"i"));}

    static int compute(Fragment fragment,int[] children,long k,boolean taproot){
        for(int child:children)if(child==0)return 0;
        int x=children.length>0?children[0]:0,y=children.length>1?children[1]:0,z=children.length>2?children[2]:0;
        int result=switch(fragment){
            case PK_K->mask("Konudemsxk");
            case PK_H->mask("Knudemsxk");
            case OLDER->iff((k&(1L<<22))!=0,"g")|iff((k&(1L<<22))==0,"h")|mask("Bzfmxk");
            case AFTER->iff(k>=500000000,"i")|iff(k<500000000,"j")|mask("Bzfmxk");
            case SHA256,RIPEMD160,HASH256,HASH160->mask("Bonudmk");
            case JUST_1->mask("Bzufmxk");
            case JUST_0->mask("Bzudemsxk");
            case WRAP_A->iff(has(x,"B"),"W")|(x&mask("ghijk"))|(x&mask("udfems"))|mask("x");
            case WRAP_S->iff(has(x,"Bo"),"W")|(x&mask("ghijk"))|(x&mask("udfemsx"));
            case WRAP_C->iff(has(x,"K"),"B")|(x&mask("ghijk"))|(x&mask("ondfem"))|mask("us");
            case WRAP_D->iff(has(x,"Vz"),"B")|iff(has(x,"z"),"o")|iff(has(x,"f"),"e")|(x&mask("ghijk"))|(x&mask("ms"))|iff(taproot,"u")|mask("ndx");
            case WRAP_V->iff(has(x,"B"),"V")|(x&mask("ghijk"))|(x&mask("zonms"))|mask("fx");
            case WRAP_J->iff(has(x,"Bn"),"B")|iff(has(x,"f"),"e")|(x&mask("ghijk"))|(x&mask("oums"))|mask("ndx");
            case WRAP_N->(x&mask("ghijk"))|(x&mask("Bzondfems"))|mask("ux");
            case AND_V->iff(has(x,"V"),y&mask("KVB"))|(x&mask("n"))|iff(has(x,"z"),y&mask("n"))
                    |iff(has(x|y,"z"),(x|y)&mask("o"))|(x&y&mask("mz"))|((x|y)&mask("s"))
                    |iff(has(y,"f")||has(x,"s"),"f")|(y&mask("ux"))|((x|y)&mask("ghij"))|iff(has(x&y,"k")&&compatible(x,y),"k");
            case AND_B->iff(has(y,"W"),x&mask("B"))|iff(has(x|y,"z"),(x|y)&mask("o"))|(x&mask("n"))|iff(has(x,"z"),y&mask("n"))
                    |iff(has(x&y,"s"),x&y&mask("e"))|(x&y&mask("dzm"))|iff(has(x&y,"f")||has(x,"sf")||has(y,"sf"),"f")
                    |((x|y)&mask("s"))|mask("ux")|((x|y)&mask("ghij"))|iff(has(x&y,"k")&&compatible(x,y),"k");
            case OR_B->iff(has(x,"Bd")&&has(y,"Wd"),"B")|iff(has(x|y,"z"),(x|y)&mask("o"))
                    |iff(has(x|y,"s")&&has(x&y,"e"),x&y&mask("m"))|(x&y&mask("zse"))|mask("dux")|((x|y)&mask("ghij"))|(x&y&mask("k"));
            case OR_D->iff(has(x,"Bdu"),y&mask("B"))|iff(has(y,"z"),x&mask("o"))|iff(has(x,"e")&&has(x|y,"s"),x&y&mask("m"))
                    |(x&y&mask("zs"))|(y&mask("ufde"))|mask("x")|((x|y)&mask("ghij"))|(x&y&mask("k"));
            case OR_C->iff(has(x,"Bdu"),y&mask("V"))|iff(has(y,"z"),x&mask("o"))|iff(has(x,"e")&&has(x|y,"s"),x&y&mask("m"))
                    |(x&y&mask("zs"))|mask("fx")|((x|y)&mask("ghij"))|(x&y&mask("k"));
            case OR_I->(x&y&mask("VBKufs"))|iff(has(x&y,"z"),"o")|iff(has(x|y,"f"),(x|y)&mask("e"))
                    |iff(has(x|y,"s"),x&y&mask("m"))|((x|y)&mask("d"))|mask("x")|((x|y)&mask("ghij"))|(x&y&mask("k"));
            case ANDOR->iff(has(x,"Bdu"),y&z&mask("BKV"))|(x&y&z&mask("z"))|iff(has(x|(y&z),"z"),(x|(y&z))&mask("o"))
                    |(y&z&mask("u"))|iff(has(x,"s")||has(y,"f"),z&mask("f"))|(z&mask("d"))|iff(has(x,"s")||has(y,"f"),z&mask("e"))
                    |iff(has(x,"e")&&has(x|y|z,"s"),x&y&z&mask("m"))|(z&(x|y)&mask("s"))|mask("x")|((x|y|z)&mask("ghij"))
                    |iff(has(x&y&z,"k")&&compatible(x,y),"k");
            case MULTI->mask("Bnudemsk");
            case MULTI_A->mask("Budemsk");
            case THRESH->{
                boolean allE=true,allM=true;int arguments=0,signatures=0,timelocks=mask("k");
                for(int index=0;index<children.length;index++){
                    int child=children[index];if(!has(child,index==0?"Bdu":"Wdu"))yield 0;
                    allE&=has(child,"e");allM&=has(child,"m");if(has(child,"s"))signatures++;
                    arguments+=has(child,"z")?0:has(child,"o")?1:2;
                    timelocks=((timelocks|child)&mask("ghij"))|iff(has(timelocks&child,"k")&&(k<=1||compatible(timelocks,child)),"k");
                }
                yield mask("Bdu")|iff(arguments==0,"z")|iff(arguments==1,"o")|iff(allE&&signatures==children.length,"e")
                        |iff(allE&&allM&&signatures>=children.length-k,"m")|iff(signatures>=children.length-k+1,"s")|timelocks;
            }
        };
        return (result&mask("BVKW"))==0?0:result;
    }
}

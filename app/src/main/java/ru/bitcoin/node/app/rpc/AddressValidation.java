package ru.bitcoin.node.app.rpc;

// Destination semantics: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.protocol.address.*;
import ru.bitcoin.node.protocol.network.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detailed Base58/Bech32 validation, including one/two-symbol checksum error locations. */
final class AddressValidation {
    private static final String BASE58="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String CHARSET="qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32M=0x2bc830a3;
    private static final int[] GENERATORS={0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3};
    private record Error(int position,int difference) { }
    private static final int[][] CONTRIBUTIONS=new int[90][32];
    private static final Map<Integer,Error> SINGLE_ERRORS=singleErrors();
    private AddressValidation() { }

    static Map<String,Object> validate(String address,NetworkParameters network) {
        // Core's locations are byte offsets into its UTF-8 string, not UTF-16 character offsets.
        String text=new String(address.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);
        String hrp=network.network()==BitcoinNetwork.MAINNET?"bc":network.network()==BitcoinNetwork.REGTEST?"bcrt":"tb";
        if(!lower(text.substring(0,Math.min(text.length(),hrp.length()))).equals(hrp)) {
            byte[] payload=null;
            try{payload=Base58Check.decode(text,21);}catch(IllegalArgumentException invalid){ }
            if(payload!=null) {
                int pub=network.network()==BitcoinNetwork.MAINNET?0:111,script=network.network()==BitcoinNetwork.MAINNET?5:196;
                if(payload.length==21&&((payload[0]&255)==pub||(payload[0]&255)==script))
                    return valid(BitcoinAddressDecoder.toScriptPubKey(text,network),network);
                if(payload.length>0&&((payload[0]&255)==pub||(payload[0]&255)==script))return invalid("Invalid length for Base58 address (P2PKH or P2SH)",List.of());
                return invalid("Invalid or unsupported Base58-encoded address.",List.of());
            }
            return invalid(rawBase58(text,100)?"Invalid checksum or length of Base58 address (P2PKH or P2SH)":"Invalid or unsupported Segwit (Bech32) or Base58 encoding.",List.of());
        }
        if(text.length()>90)return invalid("Bech32 string too long",List.of(90));
        var characters=characterErrors(text);if(!characters.isEmpty())return invalid("Invalid character or mixed case",characters);
        int separator=text.lastIndexOf('1');if(separator<0)return invalid("Missing separator",List.of());
        if(separator==0||separator+6>=text.length())return invalid("Invalid separator position",List.of(separator));
        int[] values=new int[text.length()-separator-1];
        for(int i=0;i<values.length;i++){int value=CHARSET.indexOf(lower(text.charAt(separator+1+i)));if(value<0)return invalid("Invalid Base 32 character",List.of(separator+1+i));values[i]=value;}
        String actualHrp=lower(text.substring(0,separator));int checksum=checksum(actualHrp,values);
        if(checksum!=1&&checksum!=BECH32M)return checksumError(checksum,values.length,text.length());
        int payloadSize=values.length-6;if(payloadSize==0)return invalid("Empty Bech32 data section",List.of());
        if(!actualHrp.equals(hrp))return invalid("Invalid or unsupported prefix for Segwit (Bech32) address (expected "+hrp+", got "+actualHrp+").",List.of());
        int version=values[0];
        if(version==0&&checksum!=1)return invalid("Version 0 witness address must use Bech32 checksum",List.of());
        if(version!=0&&checksum!=BECH32M)return invalid("Version 1+ witness address must use Bech32m checksum",List.of());
        var program=new java.io.ByteArrayOutputStream();int accumulator=0,bits=0;
        for(int i=1;i<payloadSize;i++){accumulator=(accumulator<<5|values[i])&0xfff;bits+=5;if(bits>=8){bits-=8;program.write(accumulator>>>bits&255);}}
        if(bits>=5||((accumulator<<(8-bits))&255)!=0)return invalid("Invalid padding in Bech32 data section",List.of());
        byte[] bytes=program.toByteArray();String size=bytes.length+(bytes.length==1?" byte":" bytes");
        if(version==0&&bytes.length!=20&&bytes.length!=32)return invalid("Invalid Bech32 v0 address program size ("+size+"), per BIP141",List.of());
        if(version>16)return invalid("Invalid Bech32 address witness version",List.of());
        if(bytes.length<2||bytes.length>40)return invalid("Invalid Bech32 address program size ("+size+")",List.of());
        byte[] script=new byte[bytes.length+2];script[0]=(byte)(version==0?0:0x50+version);script[1]=(byte)bytes.length;System.arraycopy(bytes,0,script,2,bytes.length);
        return valid(script,network);
    }
    private static Map<String,Object> valid(byte[] script,NetworkParameters network) {
        var result=new LinkedHashMap<String,Object>();result.put("isvalid",true);
        var description=ScriptJson.output(script,network);result.put("address",description.get("address"));result.put("scriptPubKey",HexFormat.of().formatHex(script));
        if(script.length==25&&script[0]==0x76){result.put("isscript",false);result.put("iswitness",false);}
        else if(script.length==23&&(script[0]&255)==0xa9){result.put("isscript",true);result.put("iswitness",false);}
        else {
            int version=script[0]==0?0:script[0]-0x50;byte[] program=Arrays.copyOfRange(script,2,script.length);
            boolean anchor=version==1&&Arrays.equals(program,new byte[]{0x4e,0x73});
            if(version==0||version==1&&program.length==32||anchor)result.put("isscript",version!=0||program.length==32);
            result.put("iswitness",true);
            if(!anchor){result.put("witness_version",version);result.put("witness_program",HexFormat.of().formatHex(program));}
        }
        return result;
    }
    private static Map<String,Object> invalid(String message,List<Integer> locations) {
        var result=new LinkedHashMap<String,Object>();result.put("isvalid",false);result.put("error_locations",locations);result.put("error",message);return result;
    }
    private static List<Integer> characterErrors(String text) {
        var result=new ArrayList<Integer>();boolean upper=false,lower=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c>='a'&&c<='z'){if(upper)result.add(i);else lower=true;}else if(c>='A'&&c<='Z'){if(lower)result.add(i);else upper=true;}else if(c<33||c>126)result.add(i);}
        return result;
    }
    private static Map<String,Object> checksumError(int checksum,int length,int stringLength) {
        List<Integer> best=List.of();int selected=0;
        for(int encoding:new int[]{1,BECH32M}) {
            int residue=checksum^encoding;var single=SINGLE_ERRORS.get(residue);List<Integer> errors=List.of();
            if(single!=null&&single.position<length)errors=List.of(stringLength-single.position-1);
            else {
                search:for(int position=0;position<length;position++)for(int difference=1;difference<32;difference++) {
                    var other=SINGLE_ERRORS.get(residue^CONTRIBUTIONS[position][difference]);
                    if(other!=null&&other.position<length&&other.position!=position){errors=List.of(stringLength-Math.max(position,other.position)-1,stringLength-Math.min(position,other.position)-1);break search;}
                }
            }
            if(best.isEmpty()||!errors.isEmpty()&&errors.size()<best.size()){best=errors;if(!errors.isEmpty())selected=encoding;}
        }
        return invalid(selected==1?"Invalid Bech32 checksum":selected==BECH32M?"Invalid Bech32m checksum":"Invalid checksum",best);
    }
    private static Map<Integer,Error> singleErrors() {
        var errors=new HashMap<Integer,Error>();
        for(int position=0;position<90;position++)for(int difference=1;difference<32;difference++) {
            int value=position==0?difference:step(CONTRIBUTIONS[position-1][difference],0);CONTRIBUTIONS[position][difference]=value;errors.put(value,new Error(position,difference));
        }
        return Map.copyOf(errors);
    }
    private static int checksum(String hrp,int[] values) {
        int checksum=1;for(int i=0;i<hrp.length();i++)checksum=step(checksum,hrp.charAt(i)>>>5);checksum=step(checksum,0);
        for(int i=0;i<hrp.length();i++)checksum=step(checksum,hrp.charAt(i)&31);for(int value:values)checksum=step(checksum,value);return checksum;
    }
    private static int step(int state,int value) {int top=state>>>25;state=(state&0x1ffffff)<<5^value;for(int i=0;i<5;i++)if(((top>>>i)&1)!=0)state^=GENERATORS[i];return state;}
    private static boolean rawBase58(String input,int maximum) {
        if(input.indexOf('\0')>=0)return false;int begin=0,end=input.length();while(begin<end&&space(input.charAt(begin)))begin++;while(end>begin&&space(input.charAt(end-1)))end--;
        if(end-begin>maximum*138/100+1)return false;int zeroes=0;while(begin+zeroes<end&&input.charAt(begin+zeroes)=='1')zeroes++;
        BigInteger value=BigInteger.ZERO;for(int i=begin;i<end;i++){int digit=BASE58.indexOf(input.charAt(i));if(digit<0)return false;value=value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));}
        return zeroes+(value.bitLength()+7)/8<=maximum;
    }
    private static String lower(String input) {var result=new StringBuilder(input.length());for(int i=0;i<input.length();i++)result.append(lower(input.charAt(i)));return result.toString();}
    private static char lower(char c) {return c>='A'&&c<='Z'?(char)(c-'A'+'a'):c;}
    private static boolean space(char c) {return c==' '||c>='\t'&&c<='\r';}
}

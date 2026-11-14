package ru.bitcoin.node.app.rpc;

// Script RPC/inference algorithms: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.script.*;
import java.util.*;

/** decodescript wrapping rules and descriptor inference with the supplied witness script. */
final class ScriptDecoding {
    private ScriptDecoding() { }
    static Map<String,Object> decode(String hex,NetworkParameters network) {
        byte[] script;
        try{script=HexFormat.of().parseHex(hex);}catch(IllegalArgumentException invalid){throw new RpcException(-8,"argument must be hexadecimal string (not '"+hex+"')");}
        var result=new LinkedHashMap<String,Object>(ScriptJson.output(script,network));result.remove("hex");String type=(String)result.get("type");
        if(!Set.of("multisig","nonstandard","pubkey","pubkeyhash","witness_v0_keyhash","witness_v0_scripthash").contains(type)||UnspendableScript.isUnspendable(script))return result;
        List<ScriptInstruction> instructions;
        try{instructions=ScriptParser.parse(script);}catch(ScriptParseException malformed){return result;}
        for(var instruction:instructions){int op=instruction.opcode();if(op>0xb9||instruction.data()!=null&&instruction.data().length>520||opSuccess(op))return result;}
        int prefix=network.network()==BitcoinNetwork.MAINNET?5:196;result.put("p2sh",BitcoinAddressEncoder.legacy(prefix,Hash160.hash(script)));
        if(type.equals("witness_v0_keyhash")||type.equals("witness_v0_scripthash"))return result;
        if(type.equals("pubkey")&&instructions.getFirst().data().length!=33)return result;
        if(type.equals("multisig"))for(var instruction:instructions.subList(1,instructions.size()-2))if(instruction.data().length!=33)return result;
        byte[] program;
        if(type.equals("pubkey"))program=Hash160.hash(instructions.getFirst().data());
        else if(type.equals("pubkeyhash"))program=Arrays.copyOfRange(script,3,23);
        else program=Sha256.hash(script);
        byte[] witness=new byte[program.length+2];witness[1]=(byte)program.length;System.arraycopy(program,0,witness,2,program.length);
        var segwit=new LinkedHashMap<String,Object>(ScriptJson.output(witness,network));
        if(program.length==32){String inferred=witnessDescriptor(script,network);if(inferred!=null)segwit.put("desc",DescriptorChecksum.add("wsh("+inferred+")"));}
        segwit.put("p2sh-segwit",BitcoinAddressEncoder.legacy(prefix,Hash160.hash(witness)));result.put("segwit",segwit);return result;
    }
    private static String witnessDescriptor(byte[] script,NetworkParameters network) {
        String top=(String)ScriptJson.output(script,network).get("desc");
        if(top.startsWith("pk(")||top.startsWith("multi("))return top.substring(0,top.lastIndexOf('#'));
        var context=new Miniscript.Context<String>() {
            public boolean taproot(){return false;}
            public String fromPublicKey(byte[] key){return key.length==33&&(key[0]==2||key[0]==3)?HexFormat.of().formatHex(key):null;}
            public String fromKeyHash(byte[] hash){return null;}
            public byte[] publicKey(String key){return HexFormat.of().parseHex(key);}
            public String keyText(String key){return key;}
        };
        var node=Miniscript.parseScript(script,context);
        return node!=null&&MiniscriptLimits.analyze(node,Comparator.naturalOrder()).sane()?MiniscriptEncoding.text(node,context):null;
    }
    private static boolean opSuccess(int op) {
        return op==80||op==98||op>=126&&op<=129||op>=131&&op<=134||op>=137&&op<=138||op>=141&&op<=142||op>=149&&op<=153||op>=187&&op<=254;
    }
}

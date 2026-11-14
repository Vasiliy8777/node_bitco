package ru.bitcoin.node.app.rpc;

// PSBT finalizer algorithms: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.hash.Ripemd160;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;
import static ru.bitcoin.node.app.rpc.MiniscriptSatisfaction.Availability;

/** Public-data finalization; does not generate signatures or access wallet keys. */
final class PsbtFinalizer {
    private final PsbtDocument document;
    private final PsbtScriptVerifier verifier;
    private final boolean transactionValid;

    PsbtFinalizer(PsbtDocument document) {
        this.document = document;
        transactionValid = document.transaction(false).isPresent();
        verifier = new PsbtScriptVerifier(document);
    }

    static Map<String,Object> finish(String encoded, boolean extract) {
        PsbtDocument document = PsbtDocument.parse(encoded);
        boolean complete = new PsbtFinalizer(document).finalizeInputs();
        var result = new LinkedHashMap<String,Object>();
        if (complete && extract) {
            Transaction unsigned = document.transaction(false).orElseThrow();
            var inputs = new ArrayList<TxIn>();
            for (int index=0;index<unsigned.inputs().size();index++) {
                TxIn original = unsigned.inputs().get(index); Fields data = document.inputs.get(index);
                inputs.add(new TxIn(original.previousOutput(),data.has(7)?data.field(7).value():new byte[0],
                        original.sequence(),data.has(8)?(Witness)data.field(8).decoded():Witness.EMPTY));
            }
            var transaction = new Transaction(unsigned.version(),inputs,unsigned.outputs(),unsigned.lockTime());
            result.put("hex",HexFormat.of().formatHex(ru.bitcoin.node.protocol.serialization.TransactionSerializer.serialize(transaction)));
        } else result.put("psbt",PsbtCodec.encode(document));
        result.put("complete",complete);
        return result;
    }

    boolean finalizeInputs() {
        boolean complete = true;
        for (int index = 0; index < document.inputs.size(); index++) complete &= finalizeInput(index);
        return complete;
    }

    private boolean finalizeInput(int index) {
        return signInput(index,true,false,null);
    }
    boolean signInput(int index,boolean respectInputSighash,boolean estimate,PsbtMissing outMissing) {
        return signInput(index,respectInputSighash,estimate,outMissing,null,true);
    }
    boolean signInput(int index,boolean respectInputSighash,boolean estimate,PsbtMissing outMissing,DescriptorProvider provider,boolean finalize) {
        if (!transactionValid) return false;
        Fields input = document.inputs.get(index);
        if (verifier.signedAndVerified(input,index,!estimate)) return true;
        TxOut coin = verifier.coin(index);
        if (coin == null) return false;
        boolean taproot = ScriptPubKeyClassifier.classify(coin.scriptPubKey()) == ScriptPubKeyType.WITNESS_V1_TAPROOT;
        long sighash = respectInputSighash ? input.number(3,taproot?0:1) : taproot?0:1;
        if(input.has(3)&&input.number(3,0)!=sighash)return false;
        for (Field field : input.values()) {
            byte[] signature = field.value();
            if (field.type() == 0x13 || field.type() == 0x14) {
                if (sighash == 0 ? signature.length != 64 : signature.length != 65 || (signature[64] & 255) != sighash) return false;
            } else if (field.type() == 2 && sighash != 0 && (signature[signature.length - 1] & 255) != sighash) return false;
        }
        boolean alreadyFinal = input.has(7) && input.field(7).value().length != 0
                || input.has(8) && ((Witness) input.field(8).decoded()).size() != 0;
        // ProduceSignature short-circuits existing final data, even when verification failed.
        if (alreadyFinal) {
            if (!input.has(0)) return false;
            clearSigningFields(input);
            return true;
        }

        var original=new Fields();original.putAll(input);
        var missing=new PsbtMissing();
        var context = new PsbtMiniscriptContext(document,index,null,estimate,missing,provider,false);
        byte[] script = coin.scriptPubKey(), redeem = null;
        Step step = step(script, input, context, index);
        if (step.solved && step.type == ScriptPubKeyType.SCRIPTHASH) {
            redeem = step.stack.getFirst();
            put(input, 4, redeem, null);
            step = step(redeem, input, context, index);
            if (step.type == ScriptPubKeyType.SCRIPTHASH) step = new Step(step.type, step.stack, false);
        }
        List<byte[]> scriptStack = new ArrayList<>(step.stack);
        Witness witness = Witness.EMPTY;
        boolean witnessProduced = false, solved = step.solved;
        if (solved && step.type == ScriptPubKeyType.WITNESS_V0_KEYHASH) {
            byte[] keyhash = step.stack.getFirst();
            byte[] pkh = new byte[25]; pkh[0] = 0x76; pkh[1] = (byte)0xa9; pkh[2] = 20;
            System.arraycopy(keyhash, 0, pkh, 3, 20); pkh[23] = (byte)0x88; pkh[24] = (byte)0xac;
            Step inner = step(pkh, input, context, index);
            solved = inner.solved; witness = new Witness(inner.stack); witnessProduced = true; scriptStack.clear();
        } else if (solved && step.type == ScriptPubKeyType.WITNESS_V0_SCRIPTHASH) {
            byte[] witnessScript = step.stack.getFirst(); put(input, 5, witnessScript, null);
            Step inner = step(witnessScript, input, context, index);
            solved = inner.solved && inner.type != ScriptPubKeyType.SCRIPTHASH
                    && inner.type != ScriptPubKeyType.WITNESS_V0_SCRIPTHASH && inner.type != ScriptPubKeyType.WITNESS_V0_KEYHASH;
            var stack = new ArrayList<>(inner.stack);
            if (!solved && stack.isEmpty()) {
                var node = Miniscript.parseScript(witnessScript, context);
                var plan = node == null ? null : MiniscriptSatisfaction.satisfy(node, context);
                solved = plan != null && plan.availability == Availability.YES;
                if (solved) stack.addAll(plan.items());
            }
            stack.add(witnessScript); witness = new Witness(stack); witnessProduced = true; scriptStack.clear();
        } else if (step.type == ScriptPubKeyType.WITNESS_V1_TAPROOT && redeem == null) {
            witnessProduced = true; if (solved) witness = new Witness(step.stack); scriptStack.clear();
        }
        if (redeem != null) scriptStack.add(redeem);
        byte[] scriptSig = pushAll(scriptStack);
        boolean complete = solved && verifier.verify(index, scriptSig, witness,estimate);
        if (!input.has(0) && !witnessProduced) {input.clear();input.putAll(original);return false;}
        if(outMissing!=null)outMissing.copyFrom(missing);
        if (complete&&finalize) {
            clearSigningFields(input);
            if (scriptSig.length != 0) put(input, 7, scriptSig, null);
            if (!witness.isEmpty()) put(input, 8, encodeWitness(witness), witness);
        }
        if (witnessProduced) put(input, 1, encodeCoin(coin), coin);
        return complete;
    }

    private record Step(ScriptPubKeyType type, List<byte[]> stack, boolean solved) { }
    private Step step(byte[] script, Fields input, PsbtMiniscriptContext context, int index) {
        ScriptPubKeyType type = classify(script);
        var stack = new ArrayList<byte[]>(); boolean solved = false;
        switch (type) {
            case PUBKEY -> {
                var signature = context.sign(HexFormat.of().formatHex(Arrays.copyOfRange(script, 1, script.length - 1)));
                solved = signature.availability() == Availability.YES;
                if (solved) stack.add(signature.bytes());
            }
            case PUBKEYHASH -> {
                String key = context.fromKeyHash(Arrays.copyOfRange(script, 3, 23));
                if (key != null) {
                    var signature = context.sign(key); solved = signature.availability() == Availability.YES;
                    if (solved) { stack.add(signature.bytes()); stack.add(context.publicKey(key)); }
                }
            }
            case MULTISIG -> {
                var instructions = ScriptParser.parse(script);
                var first = instructions.getFirst(); int required = first.opcode() >= 0x51 ? first.opcode() - 0x50 : first.data()[0];
                stack.add(new byte[0]);
                for (var instruction : instructions.subList(1, instructions.size() - 2)) {
                    var signature = context.sign(HexFormat.of().formatHex(instruction.data()));
                    if (signature.availability() == Availability.YES && stack.size() < required + 1) stack.add(signature.bytes());
                }
                solved = stack.size() == required + 1;
            }
            case SCRIPTHASH, WITNESS_V0_SCRIPTHASH -> {
                byte[] hash = type == ScriptPubKeyType.SCRIPTHASH ? Arrays.copyOfRange(script, 2, 22)
                        : Ripemd160.hash(Arrays.copyOfRange(script, 2, 34));
                byte[] provided=context.provider==null?null:context.provider.scripts.get(HexFormat.of().formatHex(hash));
                if(provided!=null){stack.add(provided);solved=true;}
                for (int field : List.of(4, 5)) {
                    if(solved)break;
                    byte[] candidate = input.has(field) ? input.field(field).value() : new byte[0];
                    if (Arrays.equals(Hash160.hash(candidate), hash)) { stack.add(candidate); solved = true; break; }
                }
                if(!solved){if(type==ScriptPubKeyType.SCRIPTHASH)context.missing.redeemScript=hash;
                    else context.missing.witnessScript=Arrays.copyOfRange(script,2,34);}
            }
            case WITNESS_V0_KEYHASH -> { stack.add(Arrays.copyOfRange(script, 2, 22)); solved = true; }
            case WITNESS_V1_TAPROOT -> {
                if(context.provider!=null)context.provider.taproot(input,Arrays.copyOfRange(script,2,34),false);
                if(context.estimating&&!input.has(0x13))put(input,0x13,new byte[64],null);
                if (!input.has(0x13)) {
                    if(context.provider!=null)context.provider.musigOrigins(input,null);
                    if (input.has(0x17)) PsbtMuSig2.aggregate(document,index,input.field(0x17).value(),null,input.has(0x18)?input.field(0x18).value():new byte[32]);
                    if (!input.has(0x13)) PsbtMuSig2.aggregate(document,index,Arrays.copyOfRange(script,2,34),null,null);
                }
                if (input.has(0x13) && input.field(0x13).value().length != 0) { stack.add(input.field(0x13).value()); solved = true; }
                else {
                    var leaves = input.values().stream().filter(field -> field.type() == 0x15).sorted((a,b) -> {
                        byte[] av = a.value(), bv = b.value();
                        int comparison = Arrays.compareUnsigned(Arrays.copyOf(av,av.length-1),Arrays.copyOf(bv,bv.length-1));
                        if (comparison == 0) comparison = Integer.compare(av[av.length-1]&255,bv[bv.length-1]&255);
                        if (comparison == 0) comparison = Integer.compare(a.key().length,b.key().length);
                        return comparison == 0 ? Arrays.compareUnsigned(a.key(),b.key()) : comparison;
                    }).toList();
                    long best = Long.MAX_VALUE; Set<String> visited = new HashSet<>();
                    for (Field field : leaves) {
                        if (!visited.add(HexFormat.of().formatHex(field.value()))) continue;
                        byte[] value = field.value(), leafScript = Arrays.copyOf(value, value.length - 1);
                        if ((value[value.length - 1]&255) != 0xc0) continue;
                        byte[] leaf = TaggedHash.hash("TapLeaf",new byte[]{(byte)0xc0},CompactSize.encode(leafScript.length),leafScript);
                        var tapContext = new PsbtMiniscriptContext(document,index,leaf,context.estimating,context.missing,context.provider,false);
                        var node = Miniscript.parseScript(leafScript,tapContext);
                        var plan = node == null ? null : MiniscriptSatisfaction.satisfy(node,tapContext);
                        if (plan == null || plan.availability != Availability.YES) continue;
                        var candidate = new ArrayList<>(plan.items()); candidate.add(leafScript); candidate.add(Arrays.copyOfRange(field.key(),1,field.key().length));
                        long size = encodeWitness(new Witness(candidate)).length;
                        if (size < best) { best = size; stack.clear(); stack.addAll(candidate); solved = true; }
                    }
                }
            }
            case ANCHOR -> solved = true;
            default -> { }
        }
        return new Step(type,stack,solved);
    }

    private static void clearSigningFields(Fields input) {
        input.entrySet().removeIf(entry -> Set.of(2,4,5,6).contains(entry.getValue().type()));
    }
    private static ScriptPubKeyType classify(byte[] script) {
        var type = ScriptPubKeyClassifier.classify(script);
        if (type != ScriptPubKeyType.NONSTANDARD) return type;
        // Core's Solver also recognizes hybrid encodings and multisig counts 17..20.
        if ((script.length == 35 || script.length == 67) && (script[0]&255) == script.length-2
                && (script[script.length-1]&255) == 0xac && publicKey(Arrays.copyOfRange(script,1,script.length-1)))
            return ScriptPubKeyType.PUBKEY;
        try {
            var instructions = ScriptParser.parse(script);
            if (instructions.size() < 4 || instructions.getLast().opcode() != 0xae) return type;
            int required = multisigNumber(instructions.getFirst()), total = multisigNumber(instructions.get(instructions.size()-2));
            if (required < 1 || total < required || total != instructions.size()-3) return type;
            for (var key : instructions.subList(1,instructions.size()-2)) if (key.data() == null || !publicKey(key.data())) return type;
            return ScriptPubKeyType.MULTISIG;
        } catch (ScriptParseException invalid) { return type; }
    }
    private static int multisigNumber(ScriptInstruction instruction) {
        int op = instruction.opcode();
        if (op >= 0x51 && op <= 0x60) return op-0x50;
        byte[] data = instruction.data();
        return op == 1 && data != null && data.length == 1 && data[0] >= 17 && data[0] <= 20 ? data[0] : -1;
    }
    private static boolean publicKey(byte[] key) {
        return key.length == 33 && (key[0] == 2 || key[0] == 3)
                || key.length == 65 && (key[0] == 4 || key[0] == 6 || key[0] == 7);
    }
    private static void put(Fields fields, int type, byte[] value, Object decoded) {
        byte[] key = {(byte)type}; fields.put(HexFormat.of().formatHex(key),new Field(type,key,value,decoded));
    }
    private static byte[] encodeWitness(Witness witness) {
        var result = new ByteArrayOutputStream(); result.writeBytes(CompactSize.encode(witness.size()));
        for (byte[] item : witness.items()) { result.writeBytes(CompactSize.encode(item.length)); result.writeBytes(item); }
        return result.toByteArray();
    }
    private static byte[] encodeCoin(TxOut coin) {
        var result = new ByteArrayOutputStream(); long value = coin.value();
        for (int i=0;i<8;i++) result.write((int)(value >>> (8*i))&255);
        byte[] script = coin.scriptPubKey(); result.writeBytes(CompactSize.encode(script.length)); result.writeBytes(script);
        return result.toByteArray();
    }
    private static byte[] pushAll(List<byte[]> stack) {
        var result = new ByteArrayOutputStream();
        for (byte[] item : stack) {
            if (item.length == 0) result.write(0);
            else if (item.length == 1 && item[0] >= 1 && item[0] <= 16) result.write(0x50+item[0]);
            else if (item.length == 1 && item[0] == (byte)0x81) result.write(0x4f);
            else {
                if (item.length < 76) result.write(item.length);
                else if (item.length <= 255) { result.write(0x4c); result.write(item.length); }
                else if (item.length <= 65535) { result.write(0x4d); result.write(item.length&255); result.write(item.length>>>8); }
                else { result.write(0x4e); for (int i=0;i<4;i++) result.write(item.length>>>(8*i)&255); }
                result.writeBytes(item);
            }
        }
        return result.toByteArray();
    }
}

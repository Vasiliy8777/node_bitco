package ru.bitcoin.node.app.rpc;

// Core script presentation algorithms: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.script.*;
import java.util.*;

/** Script presentation shared by every transaction and prevout RPC. */
final class ScriptJson {
    private ScriptJson() { }
    private static final Map<Integer, String> OPCODES = opcodeNames();
    static Map<String, Object> output(byte[] script, NetworkParameters parameters) {
        String hex = HexFormat.of().formatHex(script), type = "nonstandard", address = null, descriptor = null;
        boolean main = parameters.network() == BitcoinNetwork.MAINNET;
        if (script.length == 25 && script[0] == 0x76 && (script[1] & 255) == 0xa9 && script[2] == 20 && (script[23] & 255) == 0x88 && (script[24] & 255) == 0xac) {
            type = "pubkeyhash"; address = BitcoinAddressEncoder.legacy(main ? 0 : 111, Arrays.copyOfRange(script, 3, 23));
        } else if (script.length == 23 && (script[0] & 255) == 0xa9 && script[1] == 20 && (script[22] & 255) == 0x87) {
            type = "scripthash"; address = BitcoinAddressEncoder.legacy(main ? 5 : 196, Arrays.copyOfRange(script, 2, 22));
        } else if (script.length >= 4 && script.length <= 42 && (script[1] & 255) == script.length - 2
                && (script[0] == 0 || script[0] >= 0x51 && script[0] <= 0x60)) {
            int version = script[0] == 0 ? 0 : script[0] - 0x50; byte[] program = Arrays.copyOfRange(script, 2, script.length);
            if (version == 0 && program.length == 20) type = "witness_v0_keyhash";
            else if (version == 0 && program.length == 32) type = "witness_v0_scripthash";
            else if (version == 1 && program.length == 32) type = "witness_v1_taproot";
            else if (version == 1 && Arrays.equals(program, new byte[]{0x4e,0x73})) type = "anchor";
            else if (version != 0) type = "witness_unknown";
            if (!type.equals("nonstandard")) address = BitcoinAddressEncoder.witness(version, program, parameters);
            if (type.equals("witness_v1_taproot")) {
                try { byte[] key = new byte[33]; key[0] = 2; System.arraycopy(program, 0, key, 1, 32);
                    ru.bitcoin.node.crypto.secp256k1.PublicKey.fromBytes(key);
                    descriptor = "rawtr(" + HexFormat.of().formatHex(program) + ")";
                } catch (IllegalArgumentException ignored) { }
            }
        } else if ((script.length == 35 || script.length == 67) && (script[0] & 255) == script.length - 2 && (script[script.length - 1] & 255) == 0xac
                && publicKeyEncoding(Arrays.copyOfRange(script, 1, script.length - 1))) {
            type = "pubkey";
            if (script[1] != 6 && script[1] != 7) descriptor = "pk(" + HexFormat.of().formatHex(Arrays.copyOfRange(script, 1, script.length - 1)) + ")";
        } else {
            try {
                var instructions = ScriptParser.parse(script);
                if (script.length > 0 && script[0] == 0x6a && instructions.subList(1, instructions.size()).stream().allMatch(op -> op.opcode() <= 0x60)) type = "nulldata";
                else if (instructions.size() >= 3 && instructions.getLast().opcode() == 0xae) {
                    int required = multisigNumber(instructions.getFirst()), total = multisigNumber(instructions.get(instructions.size() - 2));
                    if (required >= 1 && total >= required && total == instructions.size() - 3) {
                        var keys = instructions.subList(1, instructions.size() - 2);
                        if (keys.stream().allMatch(key -> key.isPushData() && key.data() != null && publicKeyEncoding(key.data()))) {
                            type = "multisig";
                            if (keys.stream().noneMatch(key -> key.data()[0] == 6 || key.data()[0] == 7)) {
                                descriptor = "multi(" + required;
                                for (var key : keys) descriptor += "," + HexFormat.of().formatHex(key.data());
                                descriptor += ")";
                            }
                        }
                    }
                }
            } catch (ScriptParseException ignored) { }
        }
        if (descriptor == null) descriptor = address == null ? "raw(" + hex + ")" : "addr(" + address + ")";
        var result = new LinkedHashMap<String, Object>();
        result.put("asm", assembly(script, false)); result.put("desc", DescriptorChecksum.add(descriptor)); result.put("hex", hex); result.put("type", type);
        if (address != null) result.put("address", address);
        return result;
    }
    static String assembly(byte[] script, boolean signatures) {
        var words = new ArrayList<String>(); int position = 0;
        while (position < script.length) {
            int opcode = script[position++] & 255;
            if (opcode <= 0x4e) {
                long size = opcode;
                if (opcode >= 0x4c) {
                    int bytes = opcode == 0x4c ? 1 : opcode == 0x4d ? 2 : 4;
                    if (position + bytes > script.length) { words.add("[error]"); break; }
                    size = 0; for (int i = 0; i < bytes; i++) size |= (long) (script[position++] & 255) << (8 * i);
                }
                if (size > script.length - position) { words.add("[error]"); break; }
                byte[] data = Arrays.copyOfRange(script, position, position + (int) size); position += (int) size;
                if (data.length <= 4) {
                    long number = 0; for (int i = 0; i < data.length; i++) number |= (long) (data[i] & 255) << (8 * i);
                    if (data.length != 0 && (data[data.length - 1] & 0x80) != 0) number = -(number & ~(0x80L << (8 * (data.length - 1))));
                    words.add(Long.toString(number));
                } else {
                    String suffix = "";
                    if (signatures && !UnspendableScript.isUnspendable(script) && SignatureEncoding.isValidDerEncoding(data) && SignatureEncoding.isDefinedHashType(data)) {
                        int hashType = data[data.length - 1] & 255;
                        suffix = "[" + switch (hashType & 0x7f) { case 1 -> "ALL"; case 2 -> "NONE"; case 3 -> "SINGLE"; default -> throw new IllegalStateException(); }
                                + ((hashType & 0x80) != 0 ? "|ANYONECANPAY" : "") + "]";
                    }
                    words.add(HexFormat.of().formatHex(suffix.isEmpty() ? data : Arrays.copyOf(data, data.length - 1)) + suffix);
                }
            } else if (opcode == 0x4f) words.add("-1");
            else if (opcode >= 0x51 && opcode <= 0x60) words.add(Integer.toString(opcode - 0x50));
            else words.add(OPCODES.getOrDefault(opcode, "OP_UNKNOWN"));
        }
        return String.join(" ", words);
    }
    private static boolean publicKeyEncoding(byte[] key) {
        return key.length == 33 && (key[0] == 2 || key[0] == 3) || key.length == 65 && (key[0] == 4 || key[0] == 6 || key[0] == 7);
    }
    private static int small(int opcode) { return opcode == 0 ? 0 : opcode >= 0x51 && opcode <= 0x60 ? opcode - 0x50 : -1; }
    private static int multisigNumber(ScriptInstruction instruction) {
        int small = small(instruction.opcode());
        if (small >= 0) return small;
        // Solver also accepts minimally pushed ScriptNums 17..20.
        byte[] data = instruction.data();
        return instruction.opcode() == 1 && data != null && data.length == 1 && data[0] >= 17 && data[0] <= 20 ? data[0] : -1;
    }
    private static Map<Integer, String> opcodeNames() {
        var names = new HashMap<Integer, String>();
        for (var field : Opcode.class.getFields()) try { if (field.getType() == int.class && field.getName().startsWith("OP_")) names.put(field.getInt(null), field.getName()); }
        catch (IllegalAccessException error) { throw new ExceptionInInitializerError(error); }
        names.put(0xba, "OP_CHECKSIGADD"); names.put(0xff, "OP_INVALIDOPCODE"); return Map.copyOf(names);
    }
}

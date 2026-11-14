package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.protocol.address.BitcoinAddressEncoder;
import ru.bitcoin.node.protocol.network.*;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Multisig destination construction without a wallet or key import. */
final class MultisigCreation {
    private MultisigCreation() { }

    static Map<String, Object> create(List<?> parameters, NetworkParameters network) {
        int required = (int) TransactionCreation.integer(parameters.getFirst(), Integer.MIN_VALUE, Integer.MAX_VALUE);
        var publicKeys = new ArrayList<byte[]>(); boolean uncompressed = false, hybrid = false;
        for (Object value : (List<?>) parameters.get(1)) {
            if (!(value instanceof String text)) {
                String type = value == null ? "null" : value instanceof Boolean ? "bool" : value instanceof Number ? "number" : value instanceof List<?> ? "array" : "object";
                throw new RpcException(-3, "JSON value of type " + type + " is not of expected type string");
            }
            if (text.isEmpty() || !text.matches("(?:[0-9a-fA-F]{2})+")) throw keyError(text, "must be a hex string");
            if (text.length() != 66 && text.length() != 130) throw keyError(text, "must have a length of either 33 or 65 bytes");
            byte[] key = HexFormat.of().parseHex(text);
            try { PublicKey.fromBytes(key); } catch (IllegalArgumentException invalid) { throw keyError(text, "must be cryptographically valid."); }
            publicKeys.add(key); uncompressed |= key.length == 65; hybrid |= key[0] == 6 || key[0] == 7;
        }
        String requested = TransactionCreation.optional(parameters, 2) == null ? "legacy" : (String) parameters.get(2);
        if (!List.of("legacy", "p2sh-segwit", "bech32", "bech32m").contains(requested)) throw new RpcException(-5, "Unknown address type '" + requested + "'");
        if (requested.equals("bech32m")) throw new RpcException(-5, "createmultisig cannot create bech32m multisig addresses");
        if (required < 1) throw error("a multisignature address must require at least one key to redeem");
        if (publicKeys.size() < required) throw error("not enough keys supplied (got " + publicKeys.size() + " keys, but need at least " + required + " to redeem)");
        if (publicKeys.size() > 20) throw error("Number of keys involved in the multisignature address creation > 20\nReduce the number");
        var script = new ByteArrayOutputStream(); pushNumber(script, required);
        var multi = new StringBuilder("multi(").append(required);
        for (byte[] key : publicKeys) { script.write(key.length); script.writeBytes(key); multi.append(',').append(HexFormat.of().formatHex(key)); }
        pushNumber(script, publicKeys.size()); script.write(0xae); multi.append(')');
        byte[] redeem = script.toByteArray();
        String actual = uncompressed ? "legacy" : requested;
        if (actual.equals("legacy") && redeem.length > 520) throw error("redeemScript exceeds size limit: " + redeem.length + " > 520");
        boolean main = network.network() == BitcoinNetwork.MAINNET;
        String address, descriptor;
        if (actual.equals("legacy")) {
            address = BitcoinAddressEncoder.legacy(main ? 5 : 196, Hash160.hash(redeem));
            descriptor = hybrid ? "addr(" + address + ")" : "sh(" + multi + ")";
        } else {
            byte[] hash = Sha256.hash(redeem), witness = new byte[34]; witness[1] = 32; System.arraycopy(hash, 0, witness, 2, 32);
            address = actual.equals("bech32") ? BitcoinAddressEncoder.witness(0, hash, network)
                    : BitcoinAddressEncoder.legacy(main ? 5 : 196, Hash160.hash(witness));
            descriptor = "wsh(" + multi + ")";
            if (actual.equals("p2sh-segwit")) descriptor = "sh(" + descriptor + ")";
        }
        var result = new LinkedHashMap<String, Object>(); result.put("address", address);
        result.put("redeemScript", HexFormat.of().formatHex(redeem)); result.put("descriptor", DescriptorChecksum.add(descriptor));
        if (!actual.equals(requested)) result.put("warnings", List.of("Unable to make chosen address type, please ensure no uncompressed public keys are present."));
        return result;
    }

    private static RpcException error(String message) { return new RpcException(-8, message); }
    private static RpcException keyError(String key, String message) { return new RpcException(-5, "Pubkey \"" + key + "\" " + message); }
    private static void pushNumber(ByteArrayOutputStream script, int value) {
        if (value <= 16) script.write(0x50 + value); else { script.write(1); script.write(value); }
    }
}

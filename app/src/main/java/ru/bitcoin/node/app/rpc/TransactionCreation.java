package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.address.BitcoinAddressDecoder;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Core 32 transaction construction; errors and their ordering belong to the RPC contract. */
final class TransactionCreation {
    private TransactionCreation() { }

    static Transaction construct(List<?> params, NetworkParameters network) {
        // The typed RPC argument is evaluated before entering ConstructTransaction.
        long version = optional(params, 4) == null ? 2 : integer(params.get(4), 0, 0xffff_ffffL);
        long locktime = optional(params, 2) == null ? 0 : integer(params.get(2), Long.MIN_VALUE, Long.MAX_VALUE);
        if (locktime < 0 || locktime > 0xffff_ffffL) throw error("Invalid parameter, locktime out of range");
        if (version < 1 || version > 3) throw error("Invalid parameter, version out of range(1~3)");
        Boolean replaceable = (Boolean) optional(params, 3);
        var inputs = new ArrayList<TxIn>();
        if (params.getFirst() != null) for (Object element : (List<?>) params.getFirst()) {
            if (!(element instanceof Map<?, ?> input)) throw type(element, "object");
            Object txid = input.get("txid");
            if (!(txid instanceof String text)) throw type(txid, "string");
            int length = text.getBytes(StandardCharsets.UTF_8).length;
            if (length != 64) throw error("txid must be of length 64 (not " + length + ", for '" + text + "')");
            if (!text.matches("[0-9a-fA-F]{64}")) throw error("txid must be hexadecimal string (not '" + text + "')");
            Object vout = input.get("vout");
            if (!(vout instanceof Number)) throw error("Invalid parameter, missing vout key");
            long output = integer(vout, Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (output < 0) throw error("Invalid parameter, vout cannot be negative");
            long sequence = replaceable == null || replaceable ? 0xffff_fffdL : locktime != 0 ? 0xffff_fffeL : 0xffff_ffffL;
            if (input.get("sequence") instanceof Number value) {
                long parsed = integer(value, Long.MIN_VALUE, Long.MAX_VALUE);
                if (parsed < 0 || parsed > 0xffff_ffffL) throw error("Invalid parameter, sequence number is out of range");
                sequence = parsed;
            }
            inputs.add(new TxIn(new OutPoint(Hash256.fromDisplayHex(text), new UInt32(output)), new byte[0], new UInt32(sequence)));
        }
        Object outputsValue = params.get(1);
        if (outputsValue == null) throw error("Invalid parameter, output argument must be non-null");
        var entries = new ArrayList<Map.Entry<String, Object>>();
        if (outputsValue instanceof Map<?, ?> object) entries.addAll(entries(object));
        else if (outputsValue instanceof List<?> array) {
            for (Object element : array) {
                if (!(element instanceof Map<?, ?> object)) throw error("Invalid parameter, key-value pair not an object as expected");
                var pair = entries(object);
                if (pair.size() != 1) throw error("Invalid parameter, key-value pair must contain exactly one key");
                entries.addAll(pair);
            }
        } else throw type(outputsValue, "array");
        var outputs = new ArrayList<TxOut>();
        var destinations = new HashSet<String>(); boolean dataSeen = false;
        for (var entry : entries) {
            String name = entry.getKey(); Object value = entry.getValue();
            if (name.equals("data")) {
                if (dataSeen) throw error("Invalid parameter, duplicate key: data");
                dataSeen = true;
                String hex = value instanceof String || value instanceof Number ? value.toString()
                        : Boolean.TRUE.equals(value) ? "1" : "";
                if (hex.isEmpty() || !hex.matches("(?:[0-9a-fA-F]{2})+")) throw error("Data must be hexadecimal string (not '" + hex + "')");
                outputs.add(new TxOut(0, dataScript(HexFormat.of().parseHex(hex))));
            } else {
                byte[] script;
                try { script = BitcoinAddressDecoder.toScriptPubKey(name, network); }
                catch (IllegalArgumentException invalid) { script = null; }
                if (!(value instanceof Number) && !(value instanceof String)) throw new RpcException(-3, "Amount is not a number or string");
                long amount = NodeRpcServer.parseMoneyParam(Collections.singletonList(value), 0, 0, "amount");
                if (script == null) throw new RpcException(-5, "Invalid Bitcoin address: " + name);
                if (!destinations.add(HexFormat.of().formatHex(script))) throw error("Invalid parameter, duplicated address: " + name);
                outputs.add(new TxOut(amount, script));
            }
        }
        if (Boolean.TRUE.equals(replaceable) && !inputs.isEmpty() && inputs.stream().noneMatch(input -> input.sequence().value() < 0xffff_fffeL))
            throw error("Invalid parameter combination: Sequence number(s) contradict replaceable option");
        return new Transaction((int) version, inputs, outputs, new UInt32(locktime));
    }

    private static List<Map.Entry<String, Object>> entries(Map<?, ?> object) {
        if (object instanceof RpcProtocol.JsonObject parsed) return parsed.entries;
        var entries = new ArrayList<Map.Entry<String, Object>>();
        object.forEach((key, value) -> entries.add(new AbstractMap.SimpleImmutableEntry<>((String) key, value)));
        return entries;
    }

    static Object optional(List<?> params, int position) { return params.size() > position ? params.get(position) : null; }

    static long integer(Object value, long minimum, long maximum) {
        if (!(value instanceof Number)) throw type(value, "number");
        try {
            long result = Long.parseLong(value.toString());
            if (result < minimum || result > maximum) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException invalid) { throw new RpcException(-1, "JSON integer out of range"); }
    }

    private static RpcException type(Object value, String expected) {
        String actual = value == null ? "null" : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                : value instanceof String ? "string" : value instanceof List<?> ? "array" : "object";
        return new RpcException(-3, "JSON value of type " + actual + " is not of expected type " + expected);
    }
    private static RpcException error(String message) { return new RpcException(-8, message); }

    private static byte[] dataScript(byte[] data) {
        var script = new ByteArrayOutputStream(); script.write(0x6a);
        if (data.length < 76) script.write(data.length);
        else if (data.length <= 255) { script.write(0x4c); script.write(data.length); }
        else if (data.length <= 65535) { script.write(0x4d); script.write(data.length & 255); script.write(data.length >>> 8); }
        else { script.write(0x4e); script.writeBytes(ru.bitcoin.node.common.bytes.LittleEndian.int32(data.length)); }
        script.writeBytes(data); return script.toByteArray();
    }
}

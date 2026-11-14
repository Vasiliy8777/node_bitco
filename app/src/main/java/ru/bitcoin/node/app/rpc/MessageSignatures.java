package ru.bitcoin.node.app.rpc;

// Bitcoin message signing protocol: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.address.*;
import ru.bitcoin.node.protocol.network.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MessageSignatures {
    private MessageSignatures() { }

    static String sign(String wif, String message, NetworkParameters network) {
        byte[] payload = null, secret = null;
        try {
            payload = Base58Check.decode(wif, 34);
            int prefix = network.network() == BitcoinNetwork.MAINNET ? 128 : 239;
            if ((payload.length != 33 && payload.length != 34) || (payload[0] & 255) != prefix || payload.length == 34 && payload[33] != 1)
                throw new IllegalArgumentException("Invalid WIF");
            secret = Arrays.copyOfRange(payload, 1, 33);
            var privateKey = PrivateKey.fromBytes(secret);
            return Base64.getEncoder().encodeToString(RecoverableEcdsa.sign(hash(message), privateKey, payload.length == 34));
        } catch (IllegalArgumentException invalid) { throw new RpcException(-5, "Invalid private key"); }
        finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    static boolean verify(String address, String signature, String message, NetworkParameters network) {
        byte[] script;
        try { script = BitcoinAddressDecoder.toScriptPubKey(address, network); }
        catch (IllegalArgumentException invalid) { throw new RpcException(-5, "Invalid address"); }
        if (script.length != 25 || script[0] != 0x76) throw new RpcException(-3, "Address does not refer to key");
        byte[] compact;
        try {
            compact = Base64.getDecoder().decode(signature);
            if (signature.length() % 4 != 0 || !Base64.getEncoder().encodeToString(compact).equals(signature)) throw new IllegalArgumentException("Invalid base64");
        } catch (IllegalArgumentException invalid) { throw new RpcException(-3, "Malformed base64 encoding"); }
        var publicKey = RecoverableEcdsa.recover(hash(message), compact);
        return publicKey.isPresent() && Arrays.equals(Hash160.hash(publicKey.get()), Arrays.copyOfRange(script, 3, 23));
    }

    private static byte[] hash(String message) {
        byte[] magic = "Bitcoin Signed Message:\n".getBytes(StandardCharsets.UTF_8), bytes = message.getBytes(StandardCharsets.UTF_8);
        var stream = new ByteArrayOutputStream(); stream.writeBytes(CompactSize.encode(magic.length)); stream.writeBytes(magic);
        stream.writeBytes(CompactSize.encode(bytes.length)); stream.writeBytes(bytes); return Hash256Digest.hashBytes(stream.toByteArray());
    }
}

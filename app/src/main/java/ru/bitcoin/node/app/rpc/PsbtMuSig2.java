package ru.bitcoin.node.app.rpc;

// PSBT public signing-data handling: Copyright (c) 2024-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.script.TaprootSignatureHash;
import ru.bitcoin.node.script.ScriptExecutionException;
import java.math.BigInteger;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static ru.bitcoin.node.app.rpc.PsbtDocument.*;

/** BIP373 aggregation with BIP328 public derivations and optional Taproot tweak. */
final class PsbtMuSig2 {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] CHAINCODE = HEX.parseHex("868087ca02a6f974c4598924c36b57762d32cb45717167e300622c7167e38965");

    static byte[] aggregate(PsbtDocument document, int index, byte[] scriptKey, byte[] leaf, byte[] merkleRoot) {
        var input = document.inputs.get(index);
        if (input.values().stream().noneMatch(field -> field.type() == 0x1a)) return null;
        var transaction = document.transaction(false).orElse(null); if (transaction == null) return null;
        var verifier = new PsbtScriptVerifier(document); var coins = new ArrayList<ru.bitcoin.node.protocol.transaction.TxOut>();
        for (int i=0;i<document.inputs.size();i++) { var coin = verifier.coin(i); if (coin == null) return null; coins.add(coin); }
        long requested = input.number(3,0); if (requested > 255) return null;
        int hashType = (int)requested; byte[] message;
        try { message = TaprootSignatureHash.calculate(transaction,index,coins,hashType,null,leaf,0xffff_ffffL); }
        catch (ScriptExecutionException invalid) { return null; }
        byte[] result = null;
        var aggregations = input.values().stream().filter(field -> field.type() == 0x1a)
                .sorted((a,b) -> Arrays.compareUnsigned(a.key(),b.key())).toList();
        for (Field aggregation : aggregations) {
            byte[] aggregateKey = Arrays.copyOfRange(aggregation.key(),1,34), plainKey = aggregateKey;
            var tweaks = new ArrayList<MuSig2Aggregation.Tweak>();
            if (!Arrays.equals(Arrays.copyOfRange(aggregateKey,1,33),scriptKey)) {
                Field origin = input.get("16"+HEX.formatHex(scriptKey)); if (origin == null) continue;
                var reader = new Reader(origin.value()); long hashes = reader.compact(); reader.bytes((int)hashes*32);
                byte[] fingerprint = reader.bytes(4);
                if (reader.remaining() == 0 || !Arrays.equals(fingerprint,Arrays.copyOf(Hash160.hash(aggregateKey),4))) continue;
                byte[] chaincode = CHAINCODE.clone(); boolean valid = true;
                while (reader.remaining() != 0) {
                    long child = reader.number(4); if (child >= 0x8000_0000L || tweaks.size() >= 255) { valid = false; break; }
                    byte[] data = Arrays.copyOf(plainKey,37); for (int i=0;i<4;i++) data[33+i] = (byte)(child >>> (24-8*i));
                    byte[] derived = hmac(chaincode,data), scalar = Arrays.copyOf(derived,32);
                    var t = new BigInteger(1,scalar); if (t.compareTo(Secp256k1.DOMAIN.getN()) >= 0) { valid = false; break; }
                    var point = Secp256k1.DOMAIN.getCurve().decodePoint(plainKey).add(Secp256k1.DOMAIN.getG().multiply(t)).normalize();
                    if (point.isInfinity()) { valid = false; break; }
                    plainKey = point.getEncoded(true); chaincode = Arrays.copyOfRange(derived,32,64);
                    tweaks.add(new MuSig2Aggregation.Tweak(scalar,false));
                }
                if (!valid) return null;
                if (!Arrays.equals(Arrays.copyOfRange(plainKey,1,33),scriptKey)) continue;
            }
            if (merkleRoot != null) {
                byte[] tweak = allZero(merkleRoot) ? TaggedHash.hash("TapTweak",scriptKey) : TaggedHash.hash("TapTweak",scriptKey,merkleRoot);
                var scalar = new BigInteger(1,tweak); if (scalar.compareTo(Secp256k1.DOMAIN.getN()) >= 0) return null;
                byte[] even = new byte[33]; even[0] = 2; System.arraycopy(scriptKey,0,even,1,32);
                var point = Secp256k1.DOMAIN.getCurve().decodePoint(even).add(Secp256k1.DOMAIN.getG().multiply(scalar)).normalize();
                if (point.isInfinity()) return null;
                plainKey = point.getEncoded(true); tweaks.add(new MuSig2Aggregation.Tweak(tweak,true));
            }
            var noncesByKey = new LinkedHashMap<String,byte[]>(); var partialsByKey = new LinkedHashMap<String,byte[]>();
            byte[] leafKey = leaf == null ? new byte[32] : leaf;
            for (Field field : input.values()) if (field.type() == 0x1b || field.type() == 0x1c) {
                if (!Arrays.equals(Arrays.copyOfRange(field.key(),34,67),plainKey)) continue;
                byte[] candidateLeaf = field.key().length == 99 ? Arrays.copyOfRange(field.key(),67,99) : new byte[32];
                if (!Arrays.equals(candidateLeaf,leafKey)) continue;
                (field.type() == 0x1b ? noncesByKey : partialsByKey).putIfAbsent(HEX.formatHex(Arrays.copyOfRange(field.key(),1,34)),field.value());
            }
            var participants = new ArrayList<byte[]>(); var nonces = new ArrayList<byte[]>(); var partials = new ArrayList<byte[]>();
            boolean available = true;
            for (int offset=0;offset<aggregation.value().length;offset+=33) {
                byte[] key = Arrays.copyOfRange(aggregation.value(),offset,offset+33); String hex = HEX.formatHex(key);
                participants.add(key); byte[] nonce = noncesByKey.get(hex), partial = partialsByKey.get(hex);
                if (nonce == null || partial == null) { available = false; break; }
                nonces.add(nonce); partials.add(partial);
            }
            if (!available || noncesByKey.size() != participants.size() || partialsByKey.size() != participants.size()) continue;
            byte[] signature = MuSig2Aggregation.aggregate(participants,aggregateKey,tweaks,message,nonces,partials);
            if (signature == null) continue;
            result = hashType == 0 ? signature : Arrays.copyOf(signature,65); if (hashType != 0) result[64] = (byte)hashType;
            byte[] key;
            if (leaf == null) key = new byte[]{0x13};
            else { key = new byte[65]; key[0] = 0x14; System.arraycopy(scriptKey,0,key,1,32); System.arraycopy(leaf,0,key,33,32); }
            input.put(HEX.formatHex(key),new Field(leaf == null ? 0x13 : 0x14,key,result,null));
        }
        return result;
    }
    private static boolean allZero(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    private static byte[] hmac(byte[] key,byte[] data) {
        try { Mac mac = Mac.getInstance("HmacSHA512"); mac.init(new SecretKeySpec(key,"HmacSHA512")); return mac.doFinal(data); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}

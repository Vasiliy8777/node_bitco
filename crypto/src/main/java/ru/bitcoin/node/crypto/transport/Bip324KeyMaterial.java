package ru.bitcoin.node.crypto.transport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Directional transport keys and session identifiers derived after BIP324 ECDH.
 */
public record Bip324KeyMaterial(byte[] sendLengthKey, byte[] sendPayloadKey,
                                byte[] receiveLengthKey, byte[] receivePayloadKey,
                                byte[] sendGarbageTerminator, byte[] receiveGarbageTerminator,
                                byte[] sessionId) {
    private static final byte[] SALT_PREFIX = "bitcoin_v2_shared_secret".getBytes(StandardCharsets.US_ASCII);

    public Bip324KeyMaterial {
        sendLengthKey = copy(sendLengthKey, 32);
        sendPayloadKey = copy(sendPayloadKey, 32);
        receiveLengthKey = copy(receiveLengthKey, 32);
        receivePayloadKey = copy(receivePayloadKey, 32);
        sendGarbageTerminator = copy(sendGarbageTerminator, 16);
        receiveGarbageTerminator = copy(receiveGarbageTerminator, 16);
        sessionId = copy(sessionId, 32);
    }

    public static Bip324KeyMaterial derive(byte[] ecdhSecret, byte[] networkMagic, boolean initiating) {
        Objects.requireNonNull(ecdhSecret, "ecdhSecret");
        Objects.requireNonNull(networkMagic, "networkMagic");
        if (networkMagic.length != 4) throw new IllegalArgumentException("network magic must be 4 bytes");
        byte[] salt = new byte[SALT_PREFIX.length + 4];
        System.arraycopy(SALT_PREFIX, 0, salt, 0, SALT_PREFIX.length);
        System.arraycopy(networkMagic, 0, salt, SALT_PREFIX.length, 4);
        byte[] iL = derive(ecdhSecret, salt, "initiator_L", 32);
        byte[] iP = derive(ecdhSecret, salt, "initiator_P", 32);
        byte[] rL = derive(ecdhSecret, salt, "responder_L", 32);
        byte[] rP = derive(ecdhSecret, salt, "responder_P", 32);
        byte[] garbage = derive(ecdhSecret, salt, "garbage_terminators", 32);
        byte[] session = derive(ecdhSecret, salt, "session_id", 32);
        byte[] iG = Arrays.copyOfRange(garbage, 0, 16);
        byte[] rG = Arrays.copyOfRange(garbage, 16, 32);
        return initiating
                ? new Bip324KeyMaterial(iL, iP, rL, rP, iG, rG, session)
                : new Bip324KeyMaterial(rL, rP, iL, iP, rG, iG, session);
    }

    @Override
    public byte[] sendLengthKey() {
        return sendLengthKey.clone();
    }

    @Override
    public byte[] sendPayloadKey() {
        return sendPayloadKey.clone();
    }

    @Override
    public byte[] receiveLengthKey() {
        return receiveLengthKey.clone();
    }

    @Override
    public byte[] receivePayloadKey() {
        return receivePayloadKey.clone();
    }

    @Override
    public byte[] sendGarbageTerminator() {
        return sendGarbageTerminator.clone();
    }

    @Override
    public byte[] receiveGarbageTerminator() {
        return receiveGarbageTerminator.clone();
    }

    @Override
    public byte[] sessionId() {
        return sessionId.clone();
    }

    private static byte[] derive(byte[] secret, byte[] salt, String info, int length) {
        return HkdfSha256.derive(secret, salt, info.getBytes(StandardCharsets.US_ASCII), length);
    }

    private static byte[] copy(byte[] value, int length) {
        Objects.requireNonNull(value, "value");
        if (value.length != length) throw new IllegalArgumentException("Unexpected BIP324 key material length");
        return value.clone();
    }
}

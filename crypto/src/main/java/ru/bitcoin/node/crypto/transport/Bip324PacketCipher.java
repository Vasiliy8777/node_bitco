package ru.bitcoin.node.crypto.transport;

import java.util.Arrays;
import java.util.Objects;

/**
 * Stateful BIP324 packet encryption after the garbage-terminator handshake phase.
 */
public final class Bip324PacketCipher {
    public static final int MAX_CONTENTS_LENGTH = 0x00ff_ffff;
    public static final int LENGTH_FIELD_LENGTH = 3;
    public static final int HEADER_LENGTH = 1;
    public static final int TAG_LENGTH = 16;
    private static final int IGNORE_BIT = 0x80;

    private final Bip324Cipher.LengthCipher sendLength;
    private final Bip324Cipher.PayloadCipher sendPayload;
    private final Bip324Cipher.LengthCipher receiveLength;
    private final Bip324Cipher.PayloadCipher receivePayload;

    public Bip324PacketCipher(Bip324KeyMaterial keys) {
        Objects.requireNonNull(keys, "keys");
        sendLength = new Bip324Cipher.LengthCipher(keys.sendLengthKey());
        sendPayload = new Bip324Cipher.PayloadCipher(keys.sendPayloadKey());
        receiveLength = new Bip324Cipher.LengthCipher(keys.receiveLengthKey());
        receivePayload = new Bip324Cipher.PayloadCipher(keys.receivePayloadKey());
    }

    public byte[] encrypt(byte[] contents, byte[] aad, boolean ignore) {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(aad, "aad");
        if (contents.length > MAX_CONTENTS_LENGTH)
            throw new IllegalArgumentException("BIP324 packet contents too large");
        byte[] plaintext = new byte[HEADER_LENGTH + contents.length];
        plaintext[0] = ignore ? (byte) IGNORE_BIT : 0;
        System.arraycopy(contents, 0, plaintext, 1, contents.length);
        byte[] encryptedLength = sendLength.crypt(lengthBytes(contents.length));
        byte[] encryptedPayload = sendPayload.encrypt(aad, plaintext);
        byte[] packet = new byte[encryptedLength.length + encryptedPayload.length];
        System.arraycopy(encryptedLength, 0, packet, 0, encryptedLength.length);
        System.arraycopy(encryptedPayload, 0, packet, encryptedLength.length, encryptedPayload.length);
        return packet;
    }

    /**
     * Decrypt exactly one encrypted 3-byte length field and return the contents length.
     */
    public int decryptLength(byte[] encryptedLength) {
        Objects.requireNonNull(encryptedLength, "encryptedLength");
        if (encryptedLength.length != LENGTH_FIELD_LENGTH)
            throw new IllegalArgumentException("BIP324 length field must be 3 bytes");
        byte[] plain = receiveLength.crypt(encryptedLength);
        return (plain[0] & 0xff) | ((plain[1] & 0xff) << 8) | ((plain[2] & 0xff) << 16);
    }

    /**
     * Decrypt header+contents+tag after decryptLength has been called for the same packet.
     */
    public DecodedPacket decryptPayload(byte[] encryptedPayload, byte[] aad) {
        Objects.requireNonNull(encryptedPayload, "encryptedPayload");
        Objects.requireNonNull(aad, "aad");
        if (encryptedPayload.length < HEADER_LENGTH + TAG_LENGTH)
            throw new IllegalArgumentException("BIP324 encrypted payload too short");
        byte[] plain = receivePayload.decrypt(aad, encryptedPayload);
        if (plain == null) throw new SecurityException("BIP324 packet authentication failed");
        boolean ignore = (plain[0] & IGNORE_BIT) != 0;
        return new DecodedPacket(Arrays.copyOfRange(plain, 1, plain.length), ignore);
    }

    public record DecodedPacket(byte[] contents, boolean ignore) {
        public DecodedPacket {
            contents = contents.clone();
        }

        @Override
        public byte[] contents() {
            return contents.clone();
        }
    }

    private static byte[] lengthBytes(int length) {
        return new byte[]{(byte) length, (byte) (length >>> 8), (byte) (length >>> 16)};
    }
}

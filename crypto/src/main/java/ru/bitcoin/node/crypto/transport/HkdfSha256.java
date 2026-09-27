package ru.bitcoin.node.crypto.transport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * RFC5869 HKDF using HMAC-SHA256.
 */
public final class HkdfSha256 {
    private static final int HASH_LEN = 32;

    private HkdfSha256() {
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        Objects.requireNonNull(ikm, "ikm");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(info, "info");
        if (length < 0 || length > 255 * HASH_LEN) throw new IllegalArgumentException("Invalid HKDF output length");
        byte[] actualSalt = salt.length == 0 ? new byte[HASH_LEN] : salt;
        byte[] prk = hmac(actualSalt, ikm);
        var out = new ByteArrayOutputStream(length);
        byte[] previous = new byte[0];
        for (int i = 1; out.size() < length; i++) {
            byte[] input = new byte[previous.length + info.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) i;
            previous = hmac(prk, input);
            out.writeBytes(previous);
        }
        return Arrays.copyOf(out.toByteArray(), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

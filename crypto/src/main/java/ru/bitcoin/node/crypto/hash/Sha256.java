package ru.bitcoin.node.crypto.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import ru.bitcoin.node.common.exception.BitcoinException;

public final class Sha256 {

    private Sha256() {
    }

    public static byte[] hash(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return digest.digest(data);

        } catch (NoSuchAlgorithmException e) {
            throw new BitcoinException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}

package ru.bitcoin.node.crypto.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha1 {

    private Sha1() {
    }

    public static byte[] hash(
            byte[] input
    ) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "input must not be null"
            );
        }

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-1"
                    );

            return digest.digest(
                    input
            );

        } catch (NoSuchAlgorithmException e) {

            /*
             * SHA-1 является обязательным алгоритмом
             * стандартной Java platform.
             */
            throw new IllegalStateException(
                    "SHA-1 is not available",
                    e
            );
        }
    }
}
package ru.bitcoin.node.crypto.hash;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;

public final class Ripemd160 {

    public static final int HASH_LENGTH = 20;

    private Ripemd160() {
    }

    public static byte[] hash(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        RIPEMD160Digest digest = new RIPEMD160Digest();

        digest.update(
                data,
                0,
                data.length
        );

        byte[] result =
                new byte[digest.getDigestSize()];

        digest.doFinal(result, 0);

        return result;
    }
}

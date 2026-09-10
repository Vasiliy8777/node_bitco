package ru.bitcoin.node.crypto.hash;

import ru.bitcoin.node.common.types.Hash256;

public final class Hash256Digest {

    private Hash256Digest() {
    }

    public static Hash256 hash(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        byte[] first = Sha256.hash(data);
        byte[] second = Sha256.hash(first);

        return new Hash256(second);
    }

    public static byte[] hashBytes(byte[] data) {
        return hash(data).bytes();
    }
}

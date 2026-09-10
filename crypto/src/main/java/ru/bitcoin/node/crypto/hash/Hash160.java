package ru.bitcoin.node.crypto.hash;

public final class Hash160 {

    public static final int HASH_LENGTH = 20;

    private Hash160() {
    }

    public static byte[] hash(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        byte[] sha256 =
                Sha256.hash(data);

        return Ripemd160.hash(sha256);
    }
}
package ru.bitcoin.node.crypto.secp256k1;

import java.math.BigInteger;

public final class PrivateKey {

    public static final int LENGTH = 32;

    private final BigInteger value;

    public PrivateKey(BigInteger value) {
        if (!Secp256k1.isValidPrivateKey(value)) {
            throw new IllegalArgumentException(
                    "Private key must satisfy 1 <= d < n"
            );
        }

        this.value = value;
    }

    public static PrivateKey fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException(
                    "Private key must contain exactly 32 bytes"
            );
        }

        return new PrivateKey(
                new BigInteger(1, bytes)
        );
    }

    public BigInteger value() {
        return value;
    }

    public byte[] bytes() {
        byte[] raw = value.toByteArray();

        /*
         * BigInteger может добавить ведущий 00,
         * если старший бит равен 1.
         */
        if (raw.length == LENGTH) {
            return raw;
        }

        byte[] result = new byte[LENGTH];

        if (raw.length == LENGTH + 1 && raw[0] == 0) {
            System.arraycopy(
                    raw,
                    1,
                    result,
                    0,
                    LENGTH
            );

            return result;
        }

        if (raw.length < LENGTH) {
            System.arraycopy(
                    raw,
                    0,
                    result,
                    LENGTH - raw.length,
                    raw.length
            );

            return result;
        }

        throw new IllegalStateException(
                "Private key cannot be represented as 32 bytes"
        );
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof PrivateKey other)) {
            return false;
        }

        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}

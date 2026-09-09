package ru.bitcoin.node.common.types;

import ru.bitcoin.node.common.bytes.HexUtils;

import java.util.Arrays;

public final class Hash256 {

    public static final int LENGTH = 32;

    private final byte[] bytes;

    public Hash256(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }

        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException(
                    "Hash256 must contain exactly 32 bytes"
            );
        }

        this.bytes = bytes.clone();
    }

    public static Hash256 fromHex(String hex) {
        return new Hash256(HexUtils.decode(hex));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String toHex() {
        return HexUtils.encode(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof Hash256 other)) {
            return false;
        }

        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
package ru.bitcoin.node.common.types;

public record UInt32(long value) {

    public static final long MAX_VALUE = 0xFFFF_FFFFL;

    public UInt32 {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Value must be between 0 and 4294967295"
            );
        }
    }

    public int intBits() {
        return (int) value;
    }
}

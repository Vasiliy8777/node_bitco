package ru.bitcoin.node.common.bytes;

import java.util.HexFormat;

public final class HexUtils {

    private static final HexFormat HEX = HexFormat.of();

    private HexUtils() {
    }

    public static String encode(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex must not be null");
        }

        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }

        return HEX.parseHex(hex);
    }
}
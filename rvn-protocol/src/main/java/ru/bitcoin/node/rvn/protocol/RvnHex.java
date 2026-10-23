package ru.bitcoin.node.rvn.protocol;

public final class RvnHex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private RvnHex() {
    }

    public static String encode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = DIGITS[(bytes[i] >>> 4) & 15];
            chars[i * 2 + 1] = DIGITS[bytes[i] & 15];
        }
        return new String(chars);
    }
}

package ru.bitcoin.node.rvn.pow;

/**
 * Strict hex parser for externally verified consensus test vectors.
 */
public final class RvnPowHex {
    private RvnPowHex() {
    }

    public static byte[] parse(String hex) {
        if (hex == null || (hex.length() & 1) != 0)
            throw new IllegalArgumentException("Even-length hex required");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes");
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits[(bytes[i] & 255) >>> 4];
            out[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }
}

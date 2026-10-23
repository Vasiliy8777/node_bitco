package ru.bitcoin.node.rvn.pow;

/**
 * Explicit unsigned little-endian 32-bit word serialization.
 */
public final class RvnKawpowWords {
    private RvnKawpowWords() {
    }

    public static int readLE(byte[] data, int offset) {
        if (data == null || offset < 0 || offset > data.length - 4)
            throw new IllegalArgumentException("word offset");
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8) |
                ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24);
    }

    public static void writeLE(int value, byte[] target, int offset) {
        if (target == null || offset < 0 || offset > target.length - 4)
            throw new IllegalArgumentException("word offset");
        for (int i = 0; i < 4; i++) target[offset + i] = (byte) (value >>> (8 * i));
    }

    public static int[] decode(byte[] data) {
        if (data == null || (data.length & 3) != 0) throw new IllegalArgumentException("word bytes");
        int[] result = new int[data.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = readLE(data, i * 4);
        return result;
    }

    public static byte[] encode(int[] words) {
        if (words == null) throw new IllegalArgumentException("words");
        byte[] result = new byte[Math.multiplyExact(words.length, 4)];
        for (int i = 0; i < words.length; i++) writeLE(words[i], result, i * 4);
        return result;
    }
}

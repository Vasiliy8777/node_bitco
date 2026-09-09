package ru.bitcoin.node.common.bytes;

public final class LittleEndian {

    private LittleEndian() {
    }

    public static byte[] uint16(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("Value does not fit uint16");
        }

        return new byte[]{
                (byte) value,
                (byte) (value >>> 8)
        };
    }

    public static byte[] uint32(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Value does not fit uint32");
        }

        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    public static byte[] int32(int value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    public static byte[] int64(long value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24),
                (byte) (value >>> 32),
                (byte) (value >>> 40),
                (byte) (value >>> 48),
                (byte) (value >>> 56)
        };
    }

    public static int readUInt16(byte[] bytes, int offset) {
        checkRange(bytes, offset, 2);

        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8);
    }

    public static long readUInt32(byte[] bytes, int offset) {
        checkRange(bytes, offset, 4);

        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    public static long readInt64(byte[] bytes, int offset) {
        checkRange(bytes, offset, 8);

        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24)
                | ((bytes[offset + 4] & 0xFFL) << 32)
                | ((bytes[offset + 5] & 0xFFL) << 40)
                | ((bytes[offset + 6] & 0xFFL) << 48)
                | ((bytes[offset + 7] & 0xFFL) << 56);
    }

    private static void checkRange(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }

        if (offset < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException("Not enough bytes");
        }
    }
}
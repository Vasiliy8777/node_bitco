package ru.bitcoin.node.common.encoding;

import ru.bitcoin.node.common.bytes.LittleEndian;

public final class CompactSize {

    private CompactSize() {
    }

    public static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("CompactSize cannot be negative");
        }

        if (value < 0xFD) {
            return new byte[]{
                    (byte) value
            };
        }

        if (value <= 0xFFFFL) {
            byte[] result = new byte[3];
            result[0] = (byte) 0xFD;

            byte[] encoded = LittleEndian.uint16((int) value);

            System.arraycopy(encoded, 0, result, 1, 2);

            return result;
        }

        if (value <= 0xFFFF_FFFFL) {
            byte[] result = new byte[5];
            result[0] = (byte) 0xFE;

            byte[] encoded = LittleEndian.uint32(value);

            System.arraycopy(encoded, 0, result, 1, 4);

            return result;
        }

        byte[] result = new byte[9];
        result[0] = (byte) 0xFF;

        byte[] encoded = LittleEndian.int64(value);

        System.arraycopy(encoded, 0, result, 1, 8);

        return result;
    }
    public static Decoded decode(byte[] bytes, int offset) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }

        if (offset < 0 || offset >= bytes.length) {
            throw new IndexOutOfBoundsException("Invalid offset");
        }

        int first = bytes[offset] & 0xFF;

        if (first < 0xFD) {
            return new Decoded(first, 1);
        }

        if (first == 0xFD) {
            requireBytes(bytes, offset, 3);

            long value = LittleEndian.readUInt16(
                    bytes,
                    offset + 1
            );

            if (value < 0xFD) {
                throw new IllegalArgumentException(
                        "Non-canonical CompactSize"
                );
            }

            return new Decoded(value, 3);
        }

        if (first == 0xFE) {
            requireBytes(bytes, offset, 5);

            long value = LittleEndian.readUInt32(
                    bytes,
                    offset + 1
            );

            if (value <= 0xFFFFL) {
                throw new IllegalArgumentException(
                        "Non-canonical CompactSize"
                );
            }

            return new Decoded(value, 5);
        }

        /*
         * Полный uint64 Bitcoin здесь пока намеренно не представляем
         * обычным signed long: значения > Long.MAX_VALUE потребуют
         * отдельного unsigned-представления.
         */
        requireBytes(bytes, offset, 9);

        long value = LittleEndian.readInt64(
                bytes,
                offset + 1
        );

        if (value < 0) {
            throw new IllegalArgumentException(
                    "CompactSize value exceeds signed long range"
            );
        }

        if (value <= 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(
                    "Non-canonical CompactSize"
            );
        }

        return new Decoded(value, 9);
    }

    private static void requireBytes(
            byte[] bytes,
            int offset,
            int required
    ) {
        if (offset < 0 || offset > bytes.length - required) {
            throw new IllegalArgumentException(
                    "Unexpected end of CompactSize"
            );
        }
    }

    public record Decoded(
            long value,
            int bytesRead
    ) {
    }
}

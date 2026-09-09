package ru.bitcoin.node.common.encoding;

import java.io.ByteArrayOutputStream;

public final class VarInt {

    private VarInt() {
    }

    public static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt cannot be negative");
        }

        byte[] temp = new byte[10];
        int length = 0;

        while (true) {
            temp[length] = (byte) (value & 0x7F);

            if (length > 0) {
                temp[length] |= (byte) 0x80;
            }

            if (value <= 0x7F) {
                break;
            }

            value = (value >>> 7) - 1;
            length++;
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream(length + 1);

        do {
            out.write(temp[length] & 0xFF);
        } while (length-- > 0);

        return out.toByteArray();
    }

    public static Decoded decode(byte[] bytes, int offset) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }

        if (offset < 0 || offset >= bytes.length) {
            throw new IndexOutOfBoundsException("Invalid offset");
        }

        long value = 0;
        int position = offset;

        while (true) {
            if (position >= bytes.length) {
                throw new IllegalArgumentException(
                        "Unexpected end of VarInt"
                );
            }

            int current = bytes[position++] & 0xFF;

            if (value > (Long.MAX_VALUE >>> 7)) {
                throw new IllegalArgumentException("VarInt overflow");
            }

            value = (value << 7) | (current & 0x7F);

            if ((current & 0x80) != 0) {
                if (value == Long.MAX_VALUE) {
                    throw new IllegalArgumentException("VarInt overflow");
                }

                value++;
            } else {
                return new Decoded(
                        value,
                        position - offset
                );
            }
        }
    }

    public record Decoded(
            long value,
            int bytesRead
    ) {
    }
}

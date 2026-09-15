package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.common.encoding.CompactSize;

import java.util.Arrays;

public final class BitcoinReader {

    private final byte[] data;

    private int position;

    public BitcoinReader(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }

        this.data = data;
    }

    public int position() {
        return position;
    }

    public int remaining() {
        return data.length - position;
    }

    public boolean hasRemaining() {
        return remaining() > 0;
    }

    public int readUnsignedByte() {
        require(1);

        return data[position++] & 0xFF;
    }

    public byte[] readBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException(
                    "length cannot be negative"
            );
        }

        require(length);

        byte[] result =
                Arrays.copyOfRange(
                        data,
                        position,
                        position + length
                );

        position += length;

        return result;
    }

    public int readInt32LE() {
        require(4);

        int result =
                (data[position] & 0xFF)
                        | ((data[position + 1] & 0xFF) << 8)
                        | ((data[position + 2] & 0xFF) << 16)
                        | ((data[position + 3] & 0xFF) << 24);

        position += 4;

        return result;
    }

    public long readUInt32LE() {
        require(4);

        long result =
                (data[position] & 0xFFL)
                        | ((data[position + 1] & 0xFFL) << 8)
                        | ((data[position + 2] & 0xFFL) << 16)
                        | ((data[position + 3] & 0xFFL) << 24);

        position += 4;

        return result;
    }

    public long readInt64LE() {
        require(8);

        long result =
                (data[position] & 0xFFL)
                        | ((data[position + 1] & 0xFFL) << 8)
                        | ((data[position + 2] & 0xFFL) << 16)
                        | ((data[position + 3] & 0xFFL) << 24)
                        | ((data[position + 4] & 0xFFL) << 32)
                        | ((data[position + 5] & 0xFFL) << 40)
                        | ((data[position + 6] & 0xFFL) << 48)
                        | ((data[position + 7] & 0xFFL) << 56);

        position += 8;

        return result;
    }

    public long readCompactSize() {
        CompactSize.Decoded decoded =
                CompactSize.decode(
                        data,
                        position
                );

        position += decoded.bytesRead();

        return decoded.value();
    }

    /** Bound allocation by the bytes actually available, before creating a collection. */
    public int checkedCollectionSize(long count, int minimumElementBytes, String name) {
        if (count < 0 || count > Integer.MAX_VALUE || count > remaining() / minimumElementBytes) {
            throw new IllegalArgumentException(name + " exceeds available serialized data");
        }
        return (int) count;
    }

    public byte[] readCompactBytes() {
        long length =
                readCompactSize();

        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Byte array is too large"
            );
        }

        return readBytes((int) length);
    }

    private void require(int length) {
        if (length < 0
                || position > data.length - length) {

            throw new IllegalArgumentException(
                    "Unexpected end of Bitcoin data"
            );
        }
    }
}

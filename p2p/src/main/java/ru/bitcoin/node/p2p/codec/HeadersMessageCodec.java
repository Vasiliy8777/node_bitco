package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class HeadersMessageCodec {

    private HeadersMessageCodec() {
    }

    public static byte[] encode(
            HeadersMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        writeCompactSize(
                out,
                message.size()
        );

        for (BlockHeader header
                : message.headers()) {

            out.writeBytes(
                    BlockHeaderSerializer.serialize(
                            header
                    )
            );

            /*
             * In the headers message every block header
             * is followed by CompactSize transaction_count.
             *
             * It must be zero.
             */
            out.write(0);
        }

        return out.toByteArray();
    }

    public static HeadersMessage decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        Cursor cursor =
                new Cursor(payload);

        long count =
                cursor.readCompactSize();

        if (count > HeadersMessage.MAX_HEADERS) {
            throw new IllegalArgumentException(
                    "Too many headers: "
                            + count
            );
        }

        List<BlockHeader> headers =
                new ArrayList<>(
                        (int) count
                );

        for (int i = 0; i < count; i++) {

            BlockHeader header =
                    readBlockHeader(
                            cursor
                    );

            long transactionCount =
                    cursor.readCompactSize();

            if (transactionCount != 0) {
                throw new IllegalArgumentException(
                        "headers transaction count must be zero"
                );
            }

            headers.add(
                    header
            );
        }

        if (cursor.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Trailing bytes in headers payload"
            );
        }

        return new HeadersMessage(
                headers
        );
    }

    private static BlockHeader readBlockHeader(
            Cursor cursor
    ) {
        int version =
                cursor.readInt32LE();

        Hash256 previousBlockHash =
                new Hash256(
                        cursor.readBytes(
                                Hash256.LENGTH
                        )
                );

        Hash256 merkleRoot =
                new Hash256(
                        cursor.readBytes(
                                Hash256.LENGTH
                        )
                );

        UInt32 timestamp =
                new UInt32(
                        cursor.readUInt32LE()
                );

        UInt32 bits =
                new UInt32(
                        cursor.readUInt32LE()
                );

        UInt32 nonce =
                new UInt32(
                        cursor.readUInt32LE()
                );

        return new BlockHeader(
                version,
                previousBlockHash,
                merkleRoot,
                timestamp,
                bits,
                nonce
        );
    }

    private static void writeCompactSize(
            ByteArrayOutputStream out,
            long value
    ) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "CompactSize value must not be negative"
            );
        }

        if (value < 253) {
            out.write(
                    (int) value
            );
            return;
        }

        if (value <= 0xffffL) {
            out.write(253);
            out.write(
                    (int) value & 0xff
            );
            out.write(
                    (int) (value >>> 8) & 0xff
            );
            return;
        }

        if (value <= 0xffff_ffffL) {
            out.write(254);

            for (int i = 0; i < 4; i++) {
                out.write(
                        (int) (
                                value >>> (8 * i)
                        ) & 0xff
                );
            }

            return;
        }

        out.write(255);

        for (int i = 0; i < 8; i++) {
            out.write(
                    (int) (
                            value >>> (8 * i)
                    ) & 0xff
            );
        }
    }

    private static final class Cursor {

        private final byte[] bytes;

        private int position;

        private Cursor(
                byte[] bytes
        ) {
            this.bytes =
                    bytes;
        }

        private int readInt32LE() {
            require(4);

            int value =
                    (bytes[position] & 0xff)
                            | (
                            (bytes[position + 1] & 0xff)
                                    << 8
                    )
                            | (
                            (bytes[position + 2] & 0xff)
                                    << 16
                    )
                            | (
                            (bytes[position + 3] & 0xff)
                                    << 24
                    );

            position += 4;

            return value;
        }

        private long readUInt32LE() {
            require(4);

            long value =
                    ((long) bytes[position] & 0xff)
                            | (
                            ((long) bytes[position + 1] & 0xff)
                                    << 8
                    )
                            | (
                            ((long) bytes[position + 2] & 0xff)
                                    << 16
                    )
                            | (
                            ((long) bytes[position + 3] & 0xff)
                                    << 24
                    );

            position += 4;

            return value;
        }

        private long readCompactSize() {

            int first =
                    readUnsignedByte();

            if (first < 253) {
                return first;
            }

            if (first == 253) {

                long value =
                        readUnsignedLittleEndian(
                                2
                        );

                if (value < 253) {
                    throw new IllegalArgumentException(
                            "Non-canonical CompactSize"
                    );
                }

                return value;
            }

            if (first == 254) {

                long value =
                        readUnsignedLittleEndian(
                                4
                        );

                if (value <= 0xffffL) {
                    throw new IllegalArgumentException(
                            "Non-canonical CompactSize"
                    );
                }

                return value;
            }

            long value =
                    readUnsignedLittleEndian(
                            8
                    );

            if (value < 0) {
                throw new IllegalArgumentException(
                        "CompactSize exceeds signed long range"
                );
            }

            if (value <= 0xffff_ffffL) {
                throw new IllegalArgumentException(
                        "Non-canonical CompactSize"
                );
            }

            return value;
        }

        private int readUnsignedByte() {
            require(1);

            return bytes[position++]
                    & 0xff;
        }

        private long readUnsignedLittleEndian(
                int length
        ) {
            require(length);

            long value = 0;

            for (int i = 0; i < length; i++) {
                value |=
                        (
                                (long) bytes[position + i]
                                        & 0xff
                        )
                                << (8 * i);
            }

            position += length;

            return value;
        }

        private byte[] readBytes(
                int length
        ) {
            require(length);

            byte[] result =
                    new byte[length];

            System.arraycopy(
                    bytes,
                    position,
                    result,
                    0,
                    length
            );

            position += length;

            return result;
        }

        private boolean hasRemaining() {
            return position
                    != bytes.length;
        }

        private void require(
                int length
        ) {
            if (bytes.length - position
                    < length) {

                throw new IllegalArgumentException(
                        "Truncated headers payload"
                );
            }
        }
    }
}
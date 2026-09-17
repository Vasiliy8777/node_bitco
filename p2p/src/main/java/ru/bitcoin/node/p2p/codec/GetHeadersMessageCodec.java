package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class GetHeadersMessageCodec {

    private GetHeadersMessageCodec() {
    }

    public static byte[] encode(
            GetHeadersMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        writeInt32LE(
                out,
                message.protocolVersion()
        );

        writeCompactSize(
                out,
                message.locatorHashes().size()
        );

        for (Hash256 hash
                : message.locatorHashes()) {

            out.writeBytes(
                    hash.bytes()
            );
        }

        out.writeBytes(
                message.stopHash().bytes()
        );

        return out.toByteArray();
    }

    public static GetHeadersMessage decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        Cursor cursor =
                new Cursor(payload);

        int protocolVersion =
                cursor.readInt32LE();

        long count =
                cursor.readCompactSize();

        if (count == 0) {
            throw new IllegalArgumentException(
                    "getheaders locator must not be empty"
            );
        }

        if (count
                > GetHeadersMessage.MAX_LOCATOR_HASHES) {

            throw new IllegalArgumentException(
                    "Too many locator hashes: "
                            + count
            );
        }

        List<Hash256> locatorHashes =
                new ArrayList<>(
                        (int) count
                );

        for (int i = 0; i < count; i++) {
            locatorHashes.add(
                    new Hash256(
                            cursor.readBytes(
                                    Hash256.LENGTH
                            )
                    )
            );
        }

        Hash256 stopHash =
                new Hash256(
                        cursor.readBytes(
                                Hash256.LENGTH
                        )
                );

        if (cursor.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Trailing bytes in getheaders payload"
            );
        }

        return new GetHeadersMessage(
                protocolVersion,
                locatorHashes,
                stopHash
        );
    }

    private static void writeInt32LE(
            ByteArrayOutputStream out,
            int value
    ) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
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
            out.write((int) value);
            return;
        }

        if (value <= 0xffffL) {
            out.write(253);
            out.write((int) value & 0xff);
            out.write((int) (value >>> 8) & 0xff);
            return;
        }

        if (value <= 0xffff_ffffL) {
            out.write(254);

            for (int i = 0; i < 4; i++) {
                out.write(
                        (int) (value >>> (8 * i))
                                & 0xff
                );
            }

            return;
        }

        out.write(255);

        for (int i = 0; i < 8; i++) {
            out.write(
                    (int) (value >>> (8 * i))
                            & 0xff
            );
        }
    }

    private static final class Cursor {

        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private int readInt32LE() {
            require(4);

            int value =
                    (bytes[position] & 0xff)
                            | ((bytes[position + 1] & 0xff)
                            << 8)
                            | ((bytes[position + 2] & 0xff)
                            << 16)
                            | ((bytes[position + 3] & 0xff)
                            << 24);

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
                        readUnsignedLittleEndian(2);

                if (value < 253) {
                    throw new IllegalArgumentException(
                            "Non-canonical CompactSize"
                    );
                }

                return value;
            }

            if (first == 254) {
                long value =
                        readUnsignedLittleEndian(4);

                if (value <= 0xffffL) {
                    throw new IllegalArgumentException(
                            "Non-canonical CompactSize"
                    );
                }

                return value;
            }

            long value =
                    readUnsignedLittleEndian(8);

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

            return bytes[position++] & 0xff;
        }

        private long readUnsignedLittleEndian(
                int length
        ) {
            require(length);

            long value = 0;

            for (int i = 0; i < length; i++) {
                value |=
                        ((long) bytes[position + i]
                                & 0xff)
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
            return position != bytes.length;
        }

        private void require(
                int length
        ) {
            if (bytes.length - position < length) {
                throw new IllegalArgumentException(
                        "Truncated getheaders payload"
                );
            }
        }
    }
}
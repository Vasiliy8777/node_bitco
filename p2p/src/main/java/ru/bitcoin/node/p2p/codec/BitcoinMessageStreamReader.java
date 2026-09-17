package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.BitcoinMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class BitcoinMessageStreamReader {

    private final BitcoinMessageDecoder decoder;

    public BitcoinMessageStreamReader(
            BitcoinMessageDecoder decoder
    ) {
        if (decoder == null) {
            throw new IllegalArgumentException(
                    "decoder must not be null"
            );
        }

        this.decoder = decoder;
    }

    /**
     * Reads exactly one Bitcoin P2P message from the stream.
     *
     * Returns Optional.empty() only when the peer closes the connection
     * cleanly before the next message begins.
     *
     * If EOF occurs after part of a message has already been received,
     * the message is considered truncated and EOFException is thrown.
     */
    public Optional<BitcoinMessage> read(
            InputStream input
    ) throws IOException {

        if (input == null) {
            throw new IllegalArgumentException(
                    "input must not be null"
            );
        }

        byte[] header =
                new byte[
                        BitcoinMessageDecoder.HEADER_LENGTH
                        ];

        int first =
                input.read();

        if (first == -1) {
            return Optional.empty();
        }

        header[0] = (byte) first;

        readFully(
                input,
                header,
                1,
                header.length - 1
        );

        long payloadLength =
                readUInt32LE(
                        header,
                        16
                );

        if (payloadLength
                > BitcoinMessageDecoder.MAX_PAYLOAD_LENGTH) {

            throw new IOException(
                    "Bitcoin P2P payload exceeds maximum size: "
                            + payloadLength
            );
        }

        if (payloadLength > Integer.MAX_VALUE) {
            throw new IOException(
                    "Bitcoin P2P payload is too large: "
                            + payloadLength
            );
        }

        int length =
                (int) payloadLength;

        byte[] packet =
                new byte[
                        BitcoinMessageDecoder.HEADER_LENGTH
                                + length
                        ];

        System.arraycopy(
                header,
                0,
                packet,
                0,
                header.length
        );

        if (length > 0) {
            readFully(
                    input,
                    packet,
                    BitcoinMessageDecoder.HEADER_LENGTH,
                    length
            );
        }

        try {
            return Optional.of(
                    decoder.decode(packet)
            );
        } catch (IllegalArgumentException e) {
            throw new IOException(
                    "Invalid Bitcoin P2P message",
                    e
            );
        }
    }

    private static void readFully(
            InputStream input,
            byte[] target,
            int offset,
            int length
    ) throws IOException {

        int remaining = length;
        int position = offset;

        while (remaining > 0) {
            int read =
                    input.read(
                            target,
                            position,
                            remaining
                    );

            if (read == -1) {
                throw new EOFException(
                        "Unexpected EOF while reading Bitcoin P2P message"
                );
            }

            if (read == 0) {
                continue;
            }

            position += read;
            remaining -= read;
        }
    }

    private static long readUInt32LE(
            byte[] data,
            int offset
    ) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }
}
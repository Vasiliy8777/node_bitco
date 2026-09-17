package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BitcoinMessageDecoder {

    public static final int COMMAND_LENGTH = 12;
    public static final int CHECKSUM_LENGTH = 4;
    public static final int HEADER_LENGTH = 24;

    /*
     * Initial safety limit for a single legacy P2P payload.
     *
     * Message-specific limits will be enforced later by individual
     * message parsers as well.
     */
    public static final int MAX_PAYLOAD_LENGTH =
            4_000_000;

    private final NetworkParameters networkParameters;

    public BitcoinMessageDecoder(
            NetworkParameters networkParameters
    ) {
        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        this.networkParameters = networkParameters;
    }

    public BitcoinMessage decode(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }

        if (data.length < HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Bitcoin P2P message is shorter than header"
            );
        }

        int offset = 0;

        long magic =
                readUInt32LE(
                        data,
                        offset
                );
        offset += 4;

        if (magic != networkParameters.magic()) {
            throw new IllegalArgumentException(
                    "Invalid Bitcoin network magic"
            );
        }

        String command =
                readCommand(
                        data,
                        offset
                );
        offset += COMMAND_LENGTH;

        long payloadLength =
                readUInt32LE(
                        data,
                        offset
                );
        offset += 4;

        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "Bitcoin P2P payload exceeds maximum size"
            );
        }

        if (payloadLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Bitcoin P2P payload is too large"
            );
        }

        byte[] expectedChecksum =
                Arrays.copyOfRange(
                        data,
                        offset,
                        offset + CHECKSUM_LENGTH
                );
        offset += CHECKSUM_LENGTH;

        int length = (int) payloadLength;

        if (data.length != HEADER_LENGTH + length) {
            throw new IllegalArgumentException(
                    "Bitcoin P2P message length does not match header"
            );
        }

        byte[] payload =
                Arrays.copyOfRange(
                        data,
                        offset,
                        offset + length
                );

        byte[] actualHash =
                Hash256Digest.hashBytes(payload);

        for (int i = 0; i < CHECKSUM_LENGTH; i++) {
            if (expectedChecksum[i] != actualHash[i]) {
                throw new IllegalArgumentException(
                        "Invalid Bitcoin P2P payload checksum"
                );
            }
        }

        return new BitcoinMessage(
                command,
                payload
        );
    }

    private static String readCommand(
            byte[] data,
            int offset
    ) {
        int end = offset;

        while (end < offset + COMMAND_LENGTH
                && data[end] != 0) {

            int value = data[end] & 0xff;

            if (value < 0x20 || value > 0x7e) {
                throw new IllegalArgumentException(
                        "Invalid Bitcoin P2P command character"
                );
            }

            end++;
        }

        if (end == offset) {
            throw new IllegalArgumentException(
                    "Bitcoin P2P command must not be empty"
            );
        }

        for (int i = end; i < offset + COMMAND_LENGTH; i++) {
            if (data[i] != 0) {
                throw new IllegalArgumentException(
                        "Bitcoin P2P command has non-zero bytes after terminator"
                );
            }
        }

        return new String(
                data,
                offset,
                end - offset,
                StandardCharsets.US_ASCII
        );
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
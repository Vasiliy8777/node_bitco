package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.nio.charset.StandardCharsets;

public final class BitcoinMessageEncoder {

    public static final int COMMAND_LENGTH = 12;
    public static final int CHECKSUM_LENGTH = 4;
    public static final int HEADER_LENGTH = 24;

    private final NetworkParameters networkParameters;

    public BitcoinMessageEncoder(
            NetworkParameters networkParameters
    ) {
        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        this.networkParameters = networkParameters;
    }

    public byte[] encode(BitcoinMessage message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        byte[] payload = message.payload();

        byte[] result =
                new byte[HEADER_LENGTH + payload.length];

        int offset = 0;

        writeUInt32LE(
                result,
                offset,
                networkParameters.magic()
        );
        offset += 4;

        byte[] command =
                message.command().getBytes(
                        StandardCharsets.US_ASCII
                );

        System.arraycopy(
                command,
                0,
                result,
                offset,
                command.length
        );

        offset += COMMAND_LENGTH;

        writeUInt32LE(
                result,
                offset,
                payload.length
        );
        offset += 4;

        byte[] checksum =
                Hash256Digest.hashBytes(payload);

        System.arraycopy(
                checksum,
                0,
                result,
                offset,
                CHECKSUM_LENGTH
        );

        offset += CHECKSUM_LENGTH;

        System.arraycopy(
                payload,
                0,
                result,
                offset,
                payload.length
        );

        return result;
    }

    private static void writeUInt32LE(
            byte[] target,
            int offset,
            long value
    ) {
        target[offset] =
                (byte) value;

        target[offset + 1] =
                (byte) (value >>> 8);

        target[offset + 2] =
                (byte) (value >>> 16);

        target[offset + 3] =
                (byte) (value >>> 24);
    }
}
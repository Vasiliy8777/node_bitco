package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class VersionMessageCodec {

    private static final int MAX_USER_AGENT_LENGTH = 256;

    private VersionMessageCodec() {
    }

    public static byte[] encode(
            VersionMessage message
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
                message.version()
        );

        writeInt64LE(
                out,
                message.services()
        );

        writeInt64LE(
                out,
                message.timestamp()
        );

        writeNetworkAddress(
                out,
                message.receiverAddress()
        );

        writeNetworkAddress(
                out,
                message.senderAddress()
        );

        writeInt64LE(
                out,
                message.nonce()
        );

        byte[] userAgent =
                message.userAgent().getBytes(
                        StandardCharsets.UTF_8
                );

        out.writeBytes(
                CompactSize.encode(
                        userAgent.length
                )
        );

        out.writeBytes(userAgent);

        writeInt32LE(
                out,
                message.startHeight()
        );

        out.write(
                message.relay()
                        ? 1
                        : 0
        );

        return out.toByteArray();
    }

    public static VersionMessage decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        BitcoinReader reader =
                new BitcoinReader(payload);

        int version =
                reader.readInt32LE();

        long services =
                reader.readInt64LE();

        long timestamp =
                reader.readInt64LE();

        NetworkAddress receiverAddress =
                readNetworkAddress(reader);

        NetworkAddress senderAddress =
                readNetworkAddress(reader);

        long nonce =
                reader.readInt64LE();

        long userAgentLength =
                reader.readCompactSize();

        if (userAgentLength > MAX_USER_AGENT_LENGTH) {
            throw new IllegalArgumentException(
                    "userAgent exceeds 256 bytes"
            );
        }

        if (userAgentLength > reader.remaining()) {
            throw new IllegalArgumentException(
                    "userAgent exceeds remaining payload"
            );
        }

        String userAgent =
                new String(
                        reader.readBytes(
                                (int) userAgentLength
                        ),
                        StandardCharsets.UTF_8
                );

        int startHeight =
                reader.readInt32LE();

        boolean relay = true;

        if (reader.hasRemaining()) {
            int relayValue =
                    reader.readUnsignedByte();

            if (relayValue != 0
                    && relayValue != 1) {

                throw new IllegalArgumentException(
                        "Invalid relay flag"
                );
            }

            relay = relayValue == 1;
        }

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected trailing bytes in version message"
            );
        }

        return new VersionMessage(
                version,
                services,
                timestamp,
                receiverAddress,
                senderAddress,
                nonce,
                userAgent,
                startHeight,
                relay
        );
    }

    private static void writeNetworkAddress(
            ByteArrayOutputStream out,
            NetworkAddress address
    ) {
        writeInt64LE(
                out,
                address.services()
        );

        out.writeBytes(
                address.address()
        );

        writeUInt16BE(
                out,
                address.port()
        );
    }

    private static NetworkAddress readNetworkAddress(
            BitcoinReader reader
    ) {
        long services =
                reader.readInt64LE();

        byte[] address =
                reader.readBytes(16);

        int port =
                readUInt16BE(reader);

        return new NetworkAddress(
                services,
                address,
                port
        );
    }

    private static void writeInt32LE(
            ByteArrayOutputStream out,
            int value
    ) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeInt64LE(
            ByteArrayOutputStream out,
            long value
    ) {
        out.write((int) value);
        out.write((int) (value >>> 8));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 32));
        out.write((int) (value >>> 40));
        out.write((int) (value >>> 48));
        out.write((int) (value >>> 56));
    }

    private static void writeUInt16BE(
            ByteArrayOutputStream out,
            int value
    ) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static int readUInt16BE(
            BitcoinReader reader
    ) {
        int high =
                reader.readUnsignedByte();

        int low =
                reader.readUnsignedByte();

        return (high << 8) | low;
    }
}
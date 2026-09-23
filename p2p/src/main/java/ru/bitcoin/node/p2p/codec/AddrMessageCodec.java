package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.p2p.message.AddrEntry;
import ru.bitcoin.node.p2p.message.AddrMessage;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class AddrMessageCodec {

    private AddrMessageCodec() {
    }

    public static byte[] encode(
            AddrMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.writeBytes(
                CompactSize.encode(
                        message.size()
                )
        );

        for (AddrEntry entry : message.addresses()) {
            writeUInt32LE(
                    out,
                    entry.timestamp()
            );

            writeInt64LE(
                    out,
                    entry.services()
            );

            out.writeBytes(
                    entry.address()
            );

            writeUInt16BE(
                    out,
                    entry.port()
            );
        }

        return out.toByteArray();
    }

    public static AddrMessage decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        BitcoinReader reader =
                new BitcoinReader(payload);

        long count =
                reader.readCompactSize();

        if (count > AddrMessage.MAX_ADDRESSES) {
            throw new IllegalArgumentException(
                    "Too many addresses"
            );
        }

        int addressCount =
                reader.checkedCollectionSize(
                        count,
                        AddrEntry.SERIALIZED_LENGTH,
                        "addr entries"
                );

        List<AddrEntry> addresses =
                new ArrayList<>(addressCount);

        for (int i = 0; i < addressCount; i++) {

            long timestamp =
                    reader.readUInt32LE();

            long services =
                    reader.readInt64LE();

            byte[] address =
                    reader.readBytes(16);

            int port =
                    readUInt16BE(reader);

            NetworkAddress networkAddress =
                    new NetworkAddress(
                            services,
                            address,
                            port
                    );

            addresses.add(
                    new AddrEntry(
                            timestamp,
                            networkAddress
                    )
            );
        }

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected trailing bytes in addr message"
            );
        }

        return new AddrMessage(
                addresses
        );
    }

    private static void writeUInt32LE(
            ByteArrayOutputStream out,
            long value
    ) {
        out.write((int) value);
        out.write((int) (value >>> 8));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 24));
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
package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.p2p.message.AddrV2Entry;
import ru.bitcoin.node.p2p.message.AddrV2Message;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class AddrV2MessageCodec {

    /*
     * Minimum serialized size:
     *
     * time        4
     * services    1
     * networkId   1
     * addr length 1
     * port        2
     *
     * Address itself may have length zero for an unknown
     * future network ID.
     */
    private static final int MIN_ENTRY_SIZE = 9;

    private AddrV2MessageCodec() {
    }

    public static byte[] encode(
            AddrV2Message message
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

        for (AddrV2Entry entry
                : message.addresses()) {

            writeUInt32LE(
                    out,
                    entry.timestamp()
            );

            out.writeBytes(
                    CompactSize.encode(
                            entry.services()
                    )
            );

            out.write(
                    entry.networkId()
            );

            byte[] address =
                    entry.address();

            out.writeBytes(
                    CompactSize.encode(
                            address.length
                    )
            );

            out.writeBytes(address);

            writeUInt16BE(
                    out,
                    entry.port()
            );
        }

        return out.toByteArray();
    }

    public static AddrV2Message decode(
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

        if (count > AddrV2Message.MAX_ADDRESSES) {
            throw new IllegalArgumentException(
                    "Too many addresses"
            );
        }

        int addressCount =
                reader.checkedCollectionSize(
                        count,
                        MIN_ENTRY_SIZE,
                        "addrv2 entries"
                );

        List<AddrV2Entry> addresses =
                new ArrayList<>(addressCount);

        for (int i = 0;
             i < addressCount;
             i++) {

            long timestamp =
                    reader.readUInt32LE();

            long services =
                    reader.readCompactSize();

            int networkId =
                    reader.readUnsignedByte();

            long addressLength =
                    reader.readCompactSize();

            if (addressLength
                    > AddrV2Entry.MAX_ADDRESS_LENGTH) {

                throw new IllegalArgumentException(
                        "Address is too long: "
                                + addressLength
                );
            }

            if (addressLength
                    > Integer.MAX_VALUE) {

                throw new IllegalArgumentException(
                        "Address length is too large"
                );
            }

            byte[] address =
                    reader.readBytes(
                            (int) addressLength
                    );

            int port =
                    readUInt16BE(reader);

            addresses.add(
                    new AddrV2Entry(
                            timestamp,
                            services,
                            networkId,
                            address,
                            port
                    )
            );
        }

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected trailing bytes in addrv2 message"
            );
        }

        return new AddrV2Message(
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
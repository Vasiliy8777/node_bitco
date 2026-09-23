package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.AddrEntry;
import ru.bitcoin.node.p2p.message.AddrMessage;
import ru.bitcoin.node.p2p.message.NetworkAddress;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddrMessageCodecTest {

    @Test
    void roundTripsIpv4Address() throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        1_700_000_000L,
                        9L,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        18444
                );

        AddrMessage original =
                new AddrMessage(
                        List.of(entry)
                );

        byte[] encoded =
                AddrMessageCodec.encode(
                        original
                );

        AddrMessage decoded =
                AddrMessageCodec.decode(
                        encoded
                );

        assertEquals(
                1,
                decoded.size()
        );

        AddrEntry decodedEntry =
                decoded.addresses().get(0);

        assertEquals(
                1_700_000_000L,
                decodedEntry.timestamp()
        );

        assertEquals(
                9L,
                decodedEntry.services()
        );

        assertEquals(
                18444,
                decodedEntry.port()
        );

        assertArrayEquals(
                entry.address(),
                decodedEntry.address()
        );
    }

    @Test
    void roundTripsIpv6Address() throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        1_700_000_001L,
                        1L,
                        InetAddress.getByName(
                                "2001:db8::1"
                        ),
                        8333
                );

        byte[] encoded =
                AddrMessageCodec.encode(
                        new AddrMessage(
                                List.of(entry)
                        )
                );

        AddrMessage decoded =
                AddrMessageCodec.decode(
                        encoded
                );

        AddrEntry decodedEntry =
                decoded.addresses().get(0);

        assertEquals(
                1_700_000_001L,
                decodedEntry.timestamp()
        );

        assertEquals(
                1L,
                decodedEntry.services()
        );

        assertEquals(
                8333,
                decodedEntry.port()
        );

        assertArrayEquals(
                entry.address(),
                decodedEntry.address()
        );
    }

    @Test
    void encodesBitcoinWireFormatCorrectly()
            throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        0x01020304L,
                        1L,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        byte[] encoded =
                AddrMessageCodec.encode(
                        new AddrMessage(
                                List.of(entry)
                        )
                );

        assertEquals(
                31,
                encoded.length
        );

        // CompactSize count = 1
        assertEquals(
                0x01,
                encoded[0] & 0xFF
        );

        // timestamp uint32 LE
        assertEquals(0x04, encoded[1] & 0xFF);
        assertEquals(0x03, encoded[2] & 0xFF);
        assertEquals(0x02, encoded[3] & 0xFF);
        assertEquals(0x01, encoded[4] & 0xFF);

        // services uint64 LE = 1
        assertEquals(
                0x01,
                encoded[5] & 0xFF
        );

        for (int i = 6; i < 13; i++) {
            assertEquals(
                    0x00,
                    encoded[i] & 0xFF
            );
        }

        // IPv4-mapped IPv6:
        // 00 00 00 00 00 00 00 00 00 00 ff ff 7f 00 00 01

        int addressOffset = 13;

        for (int i = 0; i < 10; i++) {
            assertEquals(
                    0x00,
                    encoded[addressOffset + i] & 0xFF
            );
        }

        assertEquals(
                0xFF,
                encoded[addressOffset + 10] & 0xFF
        );

        assertEquals(
                0xFF,
                encoded[addressOffset + 11] & 0xFF
        );

        assertEquals(
                127,
                encoded[addressOffset + 12] & 0xFF
        );

        assertEquals(
                0,
                encoded[addressOffset + 13] & 0xFF
        );

        assertEquals(
                0,
                encoded[addressOffset + 14] & 0xFF
        );

        assertEquals(
                1,
                encoded[addressOffset + 15] & 0xFF
        );

        // 8333 = 0x208D, port is big-endian.
        assertEquals(
                0x20,
                encoded[29] & 0xFF
        );

        assertEquals(
                0x8D,
                encoded[30] & 0xFF
        );
    }

    @Test
    void supportsEmptyAddrMessage() {

        AddrMessage message =
                new AddrMessage(
                        List.of()
                );

        byte[] encoded =
                AddrMessageCodec.encode(
                        message
                );

        assertArrayEquals(
                new byte[]{0x00},
                encoded
        );

        AddrMessage decoded =
                AddrMessageCodec.decode(
                        encoded
                );

        assertEquals(
                0,
                decoded.size()
        );
    }

    @Test
    void supportsMaximumAddressCount()
            throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        1L,
                        1L,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        List<AddrEntry> entries =
                java.util.Collections.nCopies(
                        AddrMessage.MAX_ADDRESSES,
                        entry
                );

        AddrMessage message =
                new AddrMessage(
                        entries
                );

        AddrMessage decoded =
                AddrMessageCodec.decode(
                        AddrMessageCodec.encode(
                                message
                        )
                );

        assertEquals(
                AddrMessage.MAX_ADDRESSES,
                decoded.size()
        );
    }

    @Test
    void rejectsMoreThanMaximumAddresses()
            throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        1L,
                        1L,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        List<AddrEntry> entries =
                java.util.Collections.nCopies(
                        AddrMessage.MAX_ADDRESSES + 1,
                        entry
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrMessage(
                        entries
                )
        );
    }

    @Test
    void rejectsDecodedCountAboveMaximum() {

        // CompactSize 1001:
        // FD E9 03

        byte[] payload =
                new byte[]{
                        (byte) 0xFD,
                        (byte) 0xE9,
                        0x03
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrMessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsTruncatedEntry() {

        byte[] payload =
                new byte[]{
                        0x01,
                        0x00,
                        0x00,
                        0x00
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrMessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsTrailingBytes()
            throws Exception {

        AddrEntry entry =
                AddrEntry.fromIp(
                        1L,
                        1L,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        byte[] correct =
                AddrMessageCodec.encode(
                        new AddrMessage(
                                List.of(entry)
                        )
                );

        byte[] malformed =
                java.util.Arrays.copyOf(
                        correct,
                        correct.length + 1
                );

        malformed[malformed.length - 1] =
                0x01;

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrMessageCodec.decode(
                        malformed
                )
        );
    }

    @Test
    void rejectsNonCanonicalCompactSize() {

        /*
         * count = 1 encoded incorrectly using
         * CompactSize 0xFD.
         */

        byte[] payload =
                new byte[]{
                        (byte) 0xFD,
                        0x01,
                        0x00
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrMessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsInvalidTimestamp() {

        NetworkAddress address =
                new NetworkAddress(
                        1L,
                        new byte[16],
                        8333
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrEntry(
                        -1,
                        address
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrEntry(
                        0x1_0000_0000L,
                        address
                )
        );
    }

    @Test
    void rejectsNullAddressEntry() {

        List<AddrEntry> entries =
                new ArrayList<>();

        entries.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrMessage(
                        entries
                )
        );
    }
}
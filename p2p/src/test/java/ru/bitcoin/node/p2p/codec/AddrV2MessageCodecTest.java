package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.AddrV2Entry;
import ru.bitcoin.node.p2p.message.AddrV2Message;
import ru.bitcoin.node.p2p.message.AddrV2Network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddrV2MessageCodecTest {

    @Test
    void roundTripsIpv4() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        AddrV2Network.IPV4.id(),
                        new byte[]{
                                127, 0, 0, 1
                        },
                        18444
                );

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        AddrV2MessageCodec.encode(
                                new AddrV2Message(
                                        List.of(entry)
                                )
                        )
                );

        assertEquals(1, decoded.size());
        assertEquals(
                entry,
                decoded.addresses().get(0)
        );
    }

    @Test
    void roundTripsAllCurrentNetworks() {

        List<AddrV2Entry> entries =
                List.of(
                        entry(
                                AddrV2Network.IPV4,
                                4
                        ),
                        entry(
                                AddrV2Network.IPV6,
                                16
                        ),
                        entry(
                                AddrV2Network.TORV2,
                                10
                        ),
                        entry(
                                AddrV2Network.TORV3,
                                32
                        ),
                        entry(
                                AddrV2Network.I2P,
                                32
                        ),
                        entry(
                                AddrV2Network.CJDNS,
                                16
                        ),
                        entry(
                                AddrV2Network.YGGDRASIL,
                                16
                        )
                );

        AddrV2Message original =
                new AddrV2Message(entries);

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        AddrV2MessageCodec.encode(
                                original
                        )
                );

        assertEquals(
                entries,
                decoded.addresses()
        );
    }

    @Test
    void usesCompactSizeForServices() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        0x01020304L,
                        0xFCL,
                        AddrV2Network.IPV4.id(),
                        new byte[]{
                                1, 2, 3, 4
                        },
                        8333
                );

        byte[] encoded =
                AddrV2MessageCodec.encode(
                        new AddrV2Message(
                                List.of(entry)
                        )
                );

        /*
         * 0      count = 1
         * 1..4   timestamp LE
         * 5      services CompactSize = FC
         * 6      networkId = IPV4
         * 7      address length = 4
         */
        assertEquals(
                0x01,
                encoded[0] & 0xFF
        );

        assertEquals(0x04, encoded[1] & 0xFF);
        assertEquals(0x03, encoded[2] & 0xFF);
        assertEquals(0x02, encoded[3] & 0xFF);
        assertEquals(0x01, encoded[4] & 0xFF);

        assertEquals(
                0xFC,
                encoded[5] & 0xFF
        );

        assertEquals(
                AddrV2Network.IPV4.id(),
                encoded[6] & 0xFF
        );

        assertEquals(
                4,
                encoded[7] & 0xFF
        );

        // port 8333 == 0x208D, big-endian
        assertEquals(
                0x20,
                encoded[12] & 0xFF
        );

        assertEquals(
                0x8D,
                encoded[13] & 0xFF
        );
    }

    @Test
    void servicesAboveFcUseCompactSize() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        0xFDL,
                        AddrV2Network.IPV4.id(),
                        new byte[4],
                        8333
                );

        byte[] encoded =
                AddrV2MessageCodec.encode(
                        new AddrV2Message(
                                List.of(entry)
                        )
                );

        assertEquals(
                0xFD,
                encoded[5] & 0xFF
        );

        assertEquals(
                0xFD,
                encoded[6] & 0xFF
        );

        assertEquals(
                0x00,
                encoded[7] & 0xFF
        );

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        encoded
                );

        assertEquals(
                0xFDL,
                decoded.addresses()
                        .get(0)
                        .services()
        );
    }

    @Test
    void acceptsUnknownNetworkIdForParsing() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        0x80,
                        new byte[]{
                                1, 2, 3, 4, 5
                        },
                        8333
                );

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        AddrV2MessageCodec.encode(
                                new AddrV2Message(
                                        List.of(entry)
                                )
                        )
                );

        AddrV2Entry result =
                decoded.addresses().get(0);

        assertEquals(
                0x80,
                result.networkId()
        );

        assertTrue(
                result.network().isEmpty()
        );

        assertFalse(
                result.isGossipEligible()
        );

        assertArrayEquals(
                new byte[]{
                        1, 2, 3, 4, 5
                },
                result.address()
        );
    }

    @Test
    void rejectsWrongKnownNetworkLength() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.IPV4.id(),
                        new byte[5],
                        8333
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.TORV3.id(),
                        new byte[31],
                        8333
                )
        );
    }

    @Test
    void torV2IsParsedButNotGossipEligible() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.TORV2.id(),
                        new byte[10],
                        8333
                );

        assertTrue(entry.isTorV2());

        assertFalse(
                entry.isGossipEligible()
        );
    }

    @Test
    void ipv4MappedIpv6IsNotGossipEligible() {

        byte[] address =
                new byte[]{
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0,
                        (byte) 0xFF,
                        (byte) 0xFF,
                        127, 0, 0, 1
                };

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.IPV6.id(),
                        address,
                        8333
                );

        assertTrue(
                entry.isIpv4MappedIpv6()
        );

        assertFalse(
                entry.isGossipEligible()
        );
    }

    @Test
    void normalIpv6IsGossipEligible() {

        byte[] address =
                new byte[16];

        address[0] = 0x20;
        address[1] = 0x01;

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.IPV6.id(),
                        address,
                        8333
                );

        assertFalse(
                entry.isIpv4MappedIpv6()
        );

        assertTrue(
                entry.isGossipEligible()
        );
    }

    @Test
    void supportsMaximumAddressCount() {

        AddrV2Entry entry =
                entry(
                        AddrV2Network.IPV4,
                        4
                );

        List<AddrV2Entry> entries =
                Collections.nCopies(
                        AddrV2Message.MAX_ADDRESSES,
                        entry
                );

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        AddrV2MessageCodec.encode(
                                new AddrV2Message(
                                        entries
                                )
                        )
                );

        assertEquals(
                AddrV2Message.MAX_ADDRESSES,
                decoded.size()
        );
    }

    @Test
    void rejectsMoreThanMaximumAddressCount() {

        AddrV2Entry entry =
                entry(
                        AddrV2Network.IPV4,
                        4
                );

        List<AddrV2Entry> entries =
                Collections.nCopies(
                        AddrV2Message.MAX_ADDRESSES + 1,
                        entry
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrV2Message(
                        entries
                )
        );
    }

    @Test
    void rejectsDecodedCountAboveMaximum() {

        byte[] payload =
                new byte[]{
                        (byte) 0xFD,
                        (byte) 0xE9,
                        0x03
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrV2MessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsAddressLongerThan512Bytes() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrV2Entry(
                        1L,
                        1L,
                        0x80,
                        new byte[
                                AddrV2Entry.MAX_ADDRESS_LENGTH
                                        + 1
                                ],
                        8333
                )
        );
    }

    @Test
    void acceptsUnknown512ByteAddress() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        0x80,
                        new byte[
                                AddrV2Entry.MAX_ADDRESS_LENGTH
                                ],
                        8333
                );

        AddrV2Message decoded =
                AddrV2MessageCodec.decode(
                        AddrV2MessageCodec.encode(
                                new AddrV2Message(
                                        List.of(entry)
                                )
                        )
                );

        assertEquals(
                AddrV2Entry.MAX_ADDRESS_LENGTH,
                decoded.addresses()
                        .get(0)
                        .address()
                        .length
        );
    }

    @Test
    void rejectsTruncatedEntry() {

        byte[] payload =
                new byte[]{
                        0x01,
                        0x00,
                        0x00
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrV2MessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsTrailingBytes() {

        AddrV2Entry entry =
                entry(
                        AddrV2Network.IPV4,
                        4
                );

        byte[] correct =
                AddrV2MessageCodec.encode(
                        new AddrV2Message(
                                List.of(entry)
                        )
                );

        byte[] malformed =
                Arrays.copyOf(
                        correct,
                        correct.length + 1
                );

        malformed[
                malformed.length - 1
                ] = 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrV2MessageCodec.decode(
                        malformed
                )
        );
    }

    @Test
    void rejectsNonCanonicalServicesCompactSize() {

        /*
         * count       01
         * timestamp   00000000
         * services    FD 01 00
         *
         * services=1 must be encoded as 01.
         */

        byte[] payload =
                new byte[]{
                        0x01,

                        0x00, 0x00,
                        0x00, 0x00,

                        (byte) 0xFD,
                        0x01, 0x00,

                        0x01,
                        0x04,

                        127, 0, 0, 1,

                        0x20,
                        (byte) 0x8D
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> AddrV2MessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void defensiveCopiesAddress() {

        byte[] original =
                new byte[]{
                        1, 2, 3, 4
                };

        AddrV2Entry entry =
                new AddrV2Entry(
                        1L,
                        1L,
                        AddrV2Network.IPV4.id(),
                        original,
                        8333
                );

        original[0] = 99;

        assertEquals(
                1,
                entry.address()[0]
        );

        byte[] returned =
                entry.address();

        returned[0] = 88;

        assertEquals(
                1,
                entry.address()[0]
        );
    }

    @Test
    void rejectsNullEntry() {

        List<AddrV2Entry> entries =
                new ArrayList<>();

        entries.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new AddrV2Message(
                        entries
                )
        );
    }

    private static AddrV2Entry entry(
            AddrV2Network network,
            int length
    ) {
        byte[] address =
                new byte[length];

        /*
         * Keep IPv6 test data outside the
         * IPv4-mapped range.
         */
        if (network == AddrV2Network.IPV6) {
            address[0] = 0x20;
            address[1] = 0x01;
        }

        return new AddrV2Entry(
                1_700_000_000L,
                9L,
                network.id(),
                address,
                8333
        );
    }
}
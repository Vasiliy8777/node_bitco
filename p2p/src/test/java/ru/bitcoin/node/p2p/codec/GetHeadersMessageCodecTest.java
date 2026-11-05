package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetHeadersMessageCodecTest {

    @Test void acceptsNullLocatorForDirectHeaderRequestsWithoutRelaxingFraming() {
        var stop = Hash256.fromDisplayHex("12".repeat(32));
        byte[] wire = GetHeadersMessageCodec.encode(new GetHeadersMessage(70016, List.of(), stop));
        assertEquals(37, wire.length);
        assertEquals(0, wire[4]);
        var decoded = GetHeadersMessageCodec.decode(wire);
        assertEquals(List.of(), decoded.locatorHashes());
        assertEquals(stop, decoded.stopHash());
        assertThrows(IllegalArgumentException.class,
                () -> GetHeadersMessageCodec.decode(java.util.Arrays.copyOf(wire, 36)));
        assertThrows(IllegalArgumentException.class,
                () -> GetHeadersMessageCodec.decode(java.util.Arrays.copyOf(wire, 38)));
        byte[] nonCanonical = new byte[39];
        System.arraycopy(wire, 0, nonCanonical, 0, 4);
        nonCanonical[4] = (byte) 0xfd;
        System.arraycopy(wire, 5, nonCanonical, 7, 32);
        assertThrows(IllegalArgumentException.class, () -> GetHeadersMessageCodec.decode(nonCanonical));
    }

    @Test
    void shouldEncodeSingleLocatorHash()
            throws Exception {

        Hash256 locator =
                Hash256.fromDisplayHex(
                        "0f9188f13cb7b2c71f2a335e3a4fc328"
                                + "bf5beb436012afca590b1a11466e2206"
                );

        Hash256 stop =
                new Hash256(
                        new byte[32]
                );

        GetHeadersMessage message =
                new GetHeadersMessage(
                        70016,
                        List.of(locator),
                        stop
                );

        byte[] encoded =
                GetHeadersMessageCodec.encode(
                        message
                );

        /*
         * 4-byte version
         * + 1-byte CompactSize
         * + 32-byte locator
         * + 32-byte stop hash
         */
        assertEquals(
                69,
                encoded.length
        );

        /*
         * 70016 == 0x00011180
         *
         * Little endian:
         * 80 11 01 00
         */
        assertEquals(
                0x80,
                encoded[0] & 0xff
        );

        assertEquals(
                0x11,
                encoded[1] & 0xff
        );

        assertEquals(
                0x01,
                encoded[2] & 0xff
        );

        assertEquals(
                0x00,
                encoded[3] & 0xff
        );

        assertEquals(
                1,
                encoded[4] & 0xff
        );

        /*
         * Hash256.fromDisplayHex() already converted
         * the human-readable block hash into internal
         * Bitcoin byte order.
         *
         * Therefore the first wire byte is the final
         * byte of the display hash: 06.
         */
        assertEquals(
                0x06,
                encoded[5] & 0xff
        );

        assertEquals(
                0x22,
                encoded[6] & 0xff
        );

        assertEquals(
                0x6e,
                encoded[7] & 0xff
        );

        assertEquals(
                0x46,
                encoded[8] & 0xff
        );
    }

    @Test
    void shouldRoundTrip() {

        Hash256 first =
                Hash256.fromDisplayHex(
                        "00000000000000000000000000000000"
                                + "00000000000000000000000000000001"
                );

        Hash256 second =
                Hash256.fromDisplayHex(
                        "00000000000000000000000000000000"
                                + "00000000000000000000000000000002"
                );

        Hash256 stop =
                Hash256.fromDisplayHex(
                        "00000000000000000000000000000000"
                                + "00000000000000000000000000000003"
                );

        GetHeadersMessage original =
                new GetHeadersMessage(
                        70016,
                        List.of(
                                first,
                                second
                        ),
                        stop
                );

        byte[] encoded =
                GetHeadersMessageCodec.encode(
                        original
                );

        GetHeadersMessage decoded =
                GetHeadersMessageCodec.decode(
                        encoded
                );

        assertEquals(
                original.protocolVersion(),
                decoded.protocolVersion()
        );

        assertEquals(
                original.locatorHashes(),
                decoded.locatorHashes()
        );

        assertEquals(
                original.stopHash(),
                decoded.stopHash()
        );
    }

    @Test
    void shouldRejectTrailingBytes() {

        GetHeadersMessage message =
                new GetHeadersMessage(
                        70016,
                        List.of(
                                new Hash256(
                                        new byte[32]
                                )
                        ),
                        new Hash256(
                                new byte[32]
                        )
                );

        byte[] encoded =
                GetHeadersMessageCodec.encode(
                        message
                );

        byte[] malformed =
                new byte[encoded.length + 1];

        System.arraycopy(
                encoded,
                0,
                malformed,
                0,
                encoded.length
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GetHeadersMessageCodec.decode(
                                malformed
                        )
        );
    }

    @Test
    void shouldRejectTruncatedPayload() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GetHeadersMessageCodec.decode(
                                new byte[]{
                                        (byte) 0x80,
                                        0x11,
                                        0x01,
                                        0x00,
                                        0x01
                                }
                        )
        );
    }

    @Test
    void shouldRejectNonCanonicalCompactSize() {

        byte[] payload =
                new byte[
                        4
                                + 3
                                + 32
                                + 32
                        ];

        /*
         * version = 70016
         */
        payload[0] =
                (byte) 0x80;
        payload[1] =
                0x11;
        payload[2] =
                0x01;

        /*
         * Locator count = 1 encoded non-canonically
         * as fd 01 00.
         */
        payload[4] =
                (byte) 0xfd;
        payload[5] =
                0x01;
        payload[6] =
                0x00;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GetHeadersMessageCodec.decode(
                                payload
                        )
        );
    }

    @Test
    void shouldPreserveHashBytesExactly() {

        byte[] bytes =
                new byte[32];

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] =
                    (byte) i;
        }

        Hash256 locator =
                new Hash256(bytes);

        GetHeadersMessage message =
                new GetHeadersMessage(
                        70016,
                        List.of(locator),
                        new Hash256(
                                new byte[32]
                        )
                );

        byte[] encoded =
                GetHeadersMessageCodec.encode(
                        message
                );

        byte[] wireHash =
                new byte[32];

        System.arraycopy(
                encoded,
                5,
                wireHash,
                0,
                32
        );

        assertArrayEquals(
                bytes,
                wireHash
        );
    }
}

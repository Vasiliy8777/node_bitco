package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionMessageCodecTest {

    @Test
    void shouldRoundTripVersionMessage()
            throws Exception {

        NetworkAddress receiver =
                NetworkAddress.fromIp(
                        VersionMessage.DEFAULT_SERVICES,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        NetworkAddress sender =
                NetworkAddress.fromIp(
                        VersionMessage.DEFAULT_SERVICES,
                        InetAddress.getByName(
                                "192.168.1.100"
                        ),
                        8333
                );

        VersionMessage original =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        receiver,
                        sender,
                        0x0102030405060708L,
                        "/java-bitcoin-node:0.0.1/",
                        800_000,
                        false
                );

        byte[] encoded =
                VersionMessageCodec.encode(
                        original
                );

        VersionMessage decoded =
                VersionMessageCodec.decode(
                        encoded
                );

        assertEquals(
                original.version(),
                decoded.version()
        );

        assertEquals(
                original.services(),
                decoded.services()
        );

        assertEquals(
                original.timestamp(),
                decoded.timestamp()
        );

        assertEquals(
                original.receiverAddress(),
                decoded.receiverAddress()
        );

        assertEquals(
                original.senderAddress(),
                decoded.senderAddress()
        );

        assertEquals(
                original.nonce(),
                decoded.nonce()
        );

        assertEquals(
                original.userAgent(),
                decoded.userAgent()
        );

        assertEquals(
                original.startHeight(),
                decoded.startHeight()
        );

        assertFalse(
                decoded.relay()
        );
    }

    @Test
    void shouldSerializePortBigEndian()
            throws Exception {

        NetworkAddress receiver =
                NetworkAddress.fromIp(
                        0,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        VersionMessage message =
                new VersionMessage(
                        70017,
                        0,
                        0,
                        receiver,
                        NetworkAddress.unspecified(),
                        1,
                        "",
                        0,
                        true
                );

        byte[] encoded =
                VersionMessageCodec.encode(
                        message
                );

        /*
         * version       4
         * services      8
         * timestamp     8
         * recv services 8
         * recv IP      16
         *
         * receiver port starts at offset 44.
         */

        assertEquals(
                0x20,
                encoded[44] & 0xff
        );

        assertEquals(
                0x8d,
                encoded[45] & 0xff
        );
    }

    @Test
    void shouldSerializeIpv4MappedAddress()
            throws Exception {

        NetworkAddress receiver =
                NetworkAddress.fromIp(
                        0,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        8333
                );

        VersionMessage message =
                new VersionMessage(
                        70017,
                        0,
                        0,
                        receiver,
                        NetworkAddress.unspecified(),
                        1,
                        "",
                        0,
                        true
                );

        byte[] encoded =
                VersionMessageCodec.encode(
                        message
                );

        byte[] actual =
                java.util.Arrays.copyOfRange(
                        encoded,
                        28,
                        44
                );

        assertArrayEquals(
                new byte[]{
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                        0, 0,
                        (byte) 0xff,
                        (byte) 0xff,
                        127, 0, 0, 1
                },
                actual
        );
    }

    @Test
    void shouldRejectTrailingBytes()
            throws Exception {

        VersionMessage message =
                new VersionMessage(
                        70017,
                        0,
                        0,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        1,
                        "",
                        0,
                        true
                );

        byte[] encoded =
                VersionMessageCodec.encode(
                        message
                );

        byte[] invalid =
                java.util.Arrays.copyOf(
                        encoded,
                        encoded.length + 1
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> VersionMessageCodec.decode(
                        invalid
                )
        );
    }

    @Test
    void shouldRejectOversizedUserAgent() {
        String userAgent =
                "a".repeat(257);

        assertThrows(
                IllegalArgumentException.class,
                () -> new VersionMessage(
                        70017,
                        0,
                        0,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        1,
                        userAgent,
                        0,
                        true
                )
        );
    }
}
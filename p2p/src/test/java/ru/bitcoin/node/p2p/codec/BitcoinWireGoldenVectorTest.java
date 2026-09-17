package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class BitcoinWireGoldenVectorTest {

    @Test
    void shouldEncodeMainnetVerackByteForByte() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] actual =
                encoder.encode(
                        BitcoinMessages.verack()
                );

        byte[] expected =
                hex(
                        "f9beb4d9"
                                + "76657261636b000000000000"
                                + "00000000"
                                + "5df6e0e2"
                );

        assertArrayEquals(
                expected,
                actual
        );
    }

    @Test
    void shouldDecodeMainnetVerackByteForByte() {
        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] packet =
                hex(
                        "f9beb4d9"
                                + "76657261636b000000000000"
                                + "00000000"
                                + "5df6e0e2"
                );

        BitcoinMessage decoded =
                decoder.decode(packet);

        assertEquals(
                "verack",
                decoded.command()
        );

        assertEquals(
                0,
                decoded.payloadLength()
        );
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Hex string must have even length"
            );
        }

        byte[] result =
                new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            int index = i * 2;

            result[i] =
                    (byte) Integer.parseInt(
                            value.substring(
                                    index,
                                    index + 2
                            ),
                            16
                    );
        }

        return result;
    }
    @Test
    void shouldEncodeAndDecodeDeterministicVersionPacket()
            throws Exception {

        NetworkAddress receiver =
                NetworkAddress.fromIp(
                        VersionMessage.DEFAULT_SERVICES,
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        18444
                );

        NetworkAddress sender =
                NetworkAddress.fromIp(
                        VersionMessage.DEFAULT_SERVICES,
                        InetAddress.getByName(
                                "10.0.0.2"
                        ),
                        18444
                );

        VersionMessage version =
                new VersionMessage(
                        70017,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        receiver,
                        sender,
                        0x0102030405060708L,
                        "/java-bitcoin-node:0.0.1/",
                        100,
                        false
                );

        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] packet =
                encoder.encode(
                        BitcoinMessages.version(
                                version
                        )
                );

        /*
         * Verify fixed parts of the actual Bitcoin wire header.
         */

        assertArrayEquals(
                new byte[]{
                        (byte) 0xf9,
                        (byte) 0xbe,
                        (byte) 0xb4,
                        (byte) 0xd9
                },
                java.util.Arrays.copyOfRange(
                        packet,
                        0,
                        4
                )
        );

        assertArrayEquals(
                new byte[]{
                        'v', 'e', 'r', 's',
                        'i', 'o', 'n',
                        0, 0, 0, 0, 0
                },
                java.util.Arrays.copyOfRange(
                        packet,
                        4,
                        16
                )
        );

        BitcoinMessage framed =
                decoder.decode(packet);

        assertEquals(
                "version",
                framed.command()
        );

        VersionMessage decoded =
                BitcoinMessages.decodeVersion(
                        framed
                );

        assertEquals(
                70017,
                decoded.version()
        );

        assertEquals(
                VersionMessage.DEFAULT_SERVICES,
                decoded.services()
        );

        assertEquals(
                1_700_000_000L,
                decoded.timestamp()
        );

        assertEquals(
                18444,
                decoded.receiverAddress().port()
        );

        assertEquals(
                18444,
                decoded.senderAddress().port()
        );

        assertEquals(
                0x0102030405060708L,
                decoded.nonce()
        );

        assertEquals(
                "/java-bitcoin-node:0.0.1/",
                decoded.userAgent()
        );

        assertEquals(
                100,
                decoded.startHeight()
        );

        assertFalse(
                decoded.relay()
        );
    }
}
package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcoinMessageDecoderTest {

    @Test
    void shouldRoundTripMessage() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessage original =
                new BitcoinMessage(
                        "ping",
                        new byte[]{
                                1, 2, 3, 4, 5, 6, 7, 8
                        }
                );

        BitcoinMessage decoded =
                decoder.decode(
                        encoder.encode(original)
                );

        assertEquals(
                original.command(),
                decoded.command()
        );

        assertArrayEquals(
                original.payload(),
                decoded.payload()
        );
    }

    @Test
    void shouldRejectWrongNetworkMagic() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.testnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "verack",
                                new byte[0]
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(encoded)
        );
    }

    @Test
    void shouldRejectInvalidChecksum() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4
                                }
                        )
                );

        encoded[encoded.length - 1] ^= 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(encoded)
        );
    }

    @Test
    void shouldRejectNonZeroCommandBytesAfterTerminator() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[0]
                        )
                );

        encoded[8] = 0;
        encoded[9] = 'x';

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(encoded)
        );
    }

    @Test
    void shouldRejectTruncatedPayload() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4
                                }
                        )
                );

        byte[] truncated =
                java.util.Arrays.copyOf(
                        encoded,
                        encoded.length - 1
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(truncated)
        );
    }

    @Test
    void shouldRejectPayloadLargerThanMaximum() {
        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] header =
                new byte[
                        BitcoinMessageDecoder.HEADER_LENGTH
                        ];

        header[0] = (byte) 0xf9;
        header[1] = (byte) 0xbe;
        header[2] = (byte) 0xb4;
        header[3] = (byte) 0xd9;

        header[4] = 't';
        header[5] = 'e';
        header[6] = 's';
        header[7] = 't';

        long oversized =
                (long) BitcoinMessageDecoder.MAX_PAYLOAD_LENGTH
                        + 1;

        header[16] = (byte) oversized;
        header[17] = (byte) (oversized >>> 8);
        header[18] = (byte) (oversized >>> 16);
        header[19] = (byte) (oversized >>> 24);

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decode(header)
        );
    }
    @Test
    void shouldDecodeUnknownCommandAtTransportLayer() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        BitcoinMessageDecoder decoder =
                new BitcoinMessageDecoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "unknown",
                                new byte[]{
                                        1, 2, 3
                                }
                        )
                );

        BitcoinMessage decoded =
                decoder.decode(encoded);

        assertEquals(
                "unknown",
                decoded.command()
        );

        assertArrayEquals(
                new byte[]{
                        1, 2, 3
                },
                decoded.payload()
        );
    }
}
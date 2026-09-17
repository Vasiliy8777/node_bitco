package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BitcoinMessageEncoderTest {

    @Test
    void shouldEncodeMainnetMagic() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "verack",
                                new byte[0]
                        )
                );

        assertEquals(
                BitcoinMessageEncoder.HEADER_LENGTH,
                encoded.length
        );

        assertArrayEquals(
                new byte[]{
                        (byte) 0xf9,
                        (byte) 0xbe,
                        (byte) 0xb4,
                        (byte) 0xd9
                },
                new byte[]{
                        encoded[0],
                        encoded[1],
                        encoded[2],
                        encoded[3]
                }
        );
    }

    @Test
    void shouldEncodeCommandWithZeroPadding() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "ping",
                                new byte[0]
                        )
                );

        assertEquals('p', encoded[4]);
        assertEquals('i', encoded[5]);
        assertEquals('n', encoded[6]);
        assertEquals('g', encoded[7]);

        for (int i = 8; i < 16; i++) {
            assertEquals(0, encoded[i]);
        }
    }

    @Test
    void shouldEncodePayloadLengthLittleEndian() {
        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                );

        byte[] payload =
                new byte[0x0102];

        byte[] encoded =
                encoder.encode(
                        new BitcoinMessage(
                                "test",
                                payload
                        )
                );

        assertEquals(0x02, encoded[16] & 0xff);
        assertEquals(0x01, encoded[17] & 0xff);
        assertEquals(0x00, encoded[18] & 0xff);
        assertEquals(0x00, encoded[19] & 0xff);
    }
}
package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadersMessageCodecTest {

    @Test
    void shouldRoundTripRegtestGenesisHeader() {

        BlockHeader genesis =
                regtestGenesis();

        HeadersMessage original =
                new HeadersMessage(
                        List.of(genesis)
                );

        byte[] encoded =
                HeadersMessageCodec.encode(
                        original
                );

        /*
         * 1 byte count
         * + 80 byte block header
         * + 1 byte tx_count = 0
         */
        assertEquals(
                82,
                encoded.length
        );

        assertEquals(
                1,
                encoded[0] & 0xff
        );

        assertEquals(
                0,
                encoded[81] & 0xff
        );

        HeadersMessage decoded =
                HeadersMessageCodec.decode(
                        encoded
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                genesis,
                decoded.headers().get(0)
        );

        assertEquals(
                "0f9188f13cb7b2c71f2a335e3a4fc328"
                        + "bf5beb436012afca590b1a11466e2206",
                decoded.headers()
                        .get(0)
                        .hash()
                        .toDisplayHex()
        );
    }

    @Test
    void shouldDecodeEmptyHeadersMessage() {

        HeadersMessage decoded =
                HeadersMessageCodec.decode(
                        new byte[]{0x00}
                );

        assertTrue(
                decoded.isEmpty()
        );

        assertEquals(
                0,
                decoded.size()
        );
    }

    @Test
    void shouldRejectNonZeroTransactionCount() {

        byte[] encoded =
                HeadersMessageCodec.encode(
                        new HeadersMessage(
                                List.of(
                                        regtestGenesis()
                                )
                        )
                );

        encoded[encoded.length - 1] =
                0x01;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HeadersMessageCodec.decode(
                                encoded
                        )
        );
    }

    @Test
    void shouldRejectTruncatedHeader() {

        byte[] payload =
                new byte[1 + 79];

        payload[0] =
                0x01;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HeadersMessageCodec.decode(
                                payload
                        )
        );
    }

    @Test
    void shouldRejectTrailingBytes() {

        byte[] encoded =
                HeadersMessageCodec.encode(
                        new HeadersMessage(
                                List.of(
                                        regtestGenesis()
                                )
                        )
                );

        byte[] malformed =
                new byte[
                        encoded.length + 1
                        ];

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
                        HeadersMessageCodec.decode(
                                malformed
                        )
        );
    }

    @Test
    void shouldRejectMoreThanMaximumHeaders() {

        /*
         * We don't need 2001 actual headers.
         *
         * CompactSize 2001 =
         * fd d1 07
         */
        byte[] payload =
                new byte[]{
                        (byte) 0xfd,
                        (byte) 0xd1,
                        0x07
                };

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HeadersMessageCodec.decode(
                                payload
                        )
        );
    }

    @Test
    void shouldAllowExactlyMaximumHeaders() {

        List<BlockHeader> headers =
                new ArrayList<>(
                        HeadersMessage.MAX_HEADERS
                );

        BlockHeader header =
                regtestGenesis();

        for (int i = 0;
             i < HeadersMessage.MAX_HEADERS;
             i++) {

            headers.add(
                    header
            );
        }

        HeadersMessage message =
                new HeadersMessage(
                        headers
                );

        byte[] encoded =
                HeadersMessageCodec.encode(
                        message
                );

        HeadersMessage decoded =
                HeadersMessageCodec.decode(
                        encoded
                );

        assertEquals(
                HeadersMessage.MAX_HEADERS,
                decoded.size()
        );
    }

    private static BlockHeader regtestGenesis() {

        return new BlockHeader(
                1,

                new Hash256(
                        new byte[32]
                ),

                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88"
                                + "c31bc87f618f76673e2cc77a"
                                + "b2127b7afdeda33b"
                ),

                new UInt32(
                        1296688602L
                ),

                new UInt32(
                        0x207fffffL
                ),

                new UInt32(
                        2L
                )
        );
    }
}
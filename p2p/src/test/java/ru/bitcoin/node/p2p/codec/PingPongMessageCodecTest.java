package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.PingMessage;
import ru.bitcoin.node.p2p.message.PongMessage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PingPongMessageCodecTest {

    @Test
    void shouldEncodeNonceLittleEndian() {

        PingMessage message =
                new PingMessage(
                        0x0807060504030201L
                );

        assertArrayEquals(
                new byte[]{
                        0x01,
                        0x02,
                        0x03,
                        0x04,
                        0x05,
                        0x06,
                        0x07,
                        0x08
                },
                PingPongMessageCodec.encodePing(
                        message
                )
        );
    }

    @Test
    void shouldRoundTripAllBits() {

        long nonce =
                0xfedcba9876543210L;

        PingMessage ping =
                PingPongMessageCodec.decodePing(
                        PingPongMessageCodec.encodePing(
                                new PingMessage(
                                        nonce
                                )
                        )
                );

        PongMessage pong =
                PingPongMessageCodec.decodePong(
                        PingPongMessageCodec.encodePong(
                                new PongMessage(
                                        nonce
                                )
                        )
                );

        assertEquals(
                nonce,
                ping.nonce()
        );

        assertEquals(
                nonce,
                pong.nonce()
        );
    }

    @Test
    void shouldRejectWrongPayloadLength() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PingPongMessageCodec.decodePing(
                                new byte[7]
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PingPongMessageCodec.decodePong(
                                new byte[9]
                        )
        );
    }
}
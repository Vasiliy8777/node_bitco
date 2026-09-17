package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcoinMessageTest {

    @Test
    void shouldDefensivelyCopyPayload() {
        byte[] payload = {
                1, 2, 3
        };

        BitcoinMessage message =
                new BitcoinMessage(
                        "ping",
                        payload
                );

        payload[0] = 99;

        assertArrayEquals(
                new byte[]{
                        1, 2, 3
                },
                message.payload()
        );

        byte[] returned =
                message.payload();

        returned[1] = 99;

        assertArrayEquals(
                new byte[]{
                        1, 2, 3
                },
                message.payload()
        );
    }

    @Test
    void shouldRejectCommandLongerThanTwelveBytes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BitcoinMessage(
                        "1234567890123",
                        new byte[0]
                )
        );
    }

    @Test
    void shouldRejectNonPrintableCommand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BitcoinMessage(
                        "ping\n",
                        new byte[0]
                )
        );
    }
}
package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GetAddrMessageTest {

    @Test
    void createsGetAddrWithEmptyPayload() {

        BitcoinMessage message =
                BitcoinMessages.getAddr();

        assertEquals(
                "getaddr",
                message.command()
        );

        assertEquals(
                0,
                message.payloadLength()
        );

        assertArrayEquals(
                new byte[0],
                message.payload()
        );
    }

    @Test
    void validatesCorrectGetAddr() {

        BitcoinMessage message =
                BitcoinMessages.getAddr();

        assertDoesNotThrow(
                () -> BitcoinMessages.validateGetAddr(
                        message
                )
        );
    }

    @Test
    void rejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "addr",
                        new byte[0]
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.validateGetAddr(
                        message
                )
        );
    }

    @Test
    void rejectsGetAddrWithPayload() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "getaddr",
                        new byte[]{
                                0x01
                        }
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> BitcoinMessages.validateGetAddr(
                                message
                        )
                );

        assertEquals(
                "getaddr message must have empty payload",
                exception.getMessage()
        );
    }

    @Test
    void rejectsNullMessage() {

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.validateGetAddr(
                        null
                )
        );
    }
}
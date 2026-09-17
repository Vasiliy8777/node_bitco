package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerAckMessageTest {

    @Test
    void shouldHaveEmptyPayload() {
        assertEquals(
                0,
                VerAckMessage.INSTANCE.payload().length
        );
    }
}
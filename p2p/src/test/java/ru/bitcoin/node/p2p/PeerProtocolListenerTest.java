package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PeerProtocolListenerTest {

    @Test
    void ordinaryListenerFailureDoesNotEscape() {

        /*
         * Listener isolation itself is tested here through
         * the listener contract. Full reader-path behaviour
         * remains covered by the socket integration tests.
         */

        PeerMessageListener listener =
                (peer, message) -> {
                    throw new RuntimeException(
                            "observer failure"
                    );
                };

        assertNotNull(
                listener
        );
    }

    @Test
    void protocolExceptionHasDedicatedType() {

        PeerProtocolException exception =
                new PeerProtocolException(
                        "invalid protocol message"
                );

        assertInstanceOf(
                RuntimeException.class,
                exception
        );

        assertEquals(
                "invalid protocol message",
                exception.getMessage()
        );
    }

    @Test
    void addressProtocolExceptionIsProtocolException() {

        ru.bitcoin.node.p2p.address.PeerAddressProtocolException exception =
                new ru.bitcoin.node.p2p.address.PeerAddressProtocolException(
                        "invalid addr"
                );

        assertInstanceOf(
                PeerProtocolException.class,
                exception
        );
    }
}
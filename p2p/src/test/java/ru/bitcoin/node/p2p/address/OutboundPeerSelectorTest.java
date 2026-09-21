package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboundPeerSelectorTest {

    @Test
    void shouldReturnKnownPeerAddresses()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress first =
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.10"
                        ),
                        8333,
                        0L
                );

        PeerAddress second =
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.11"
                        ),
                        8333,
                        0L
                );

        Instant now =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        manager.add(
                first,
                now
        );

        manager.add(
                second,
                now
        );

        OutboundPeerSelector selector =
                new OutboundPeerSelector(
                        manager
                );

        assertEquals(
                List.of(
                        first,
                        second
                ),
                selector.candidates()
        );
    }
}
package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundPeerSelectorTest {

    @Test
    void shouldSelectKnownPeerAddress()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.10"
                        ),
                        8333,
                        0L
                );

        manager.add(
                address,
                Instant.ofEpochSecond(
                        1_700_000_000L
                )
        );

        OutboundPeerSelector selector =
                new OutboundPeerSelector(
                        manager
                );

        assertEquals(
                address,
                selector.select()
                        .orElseThrow()
        );
    }

    @Test
    void shouldExcludeAlreadyAttemptedAddress()
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

        PeerAddress selected =
                selector.select(
                                Set.of(
                                        first
                                )
                        )
                        .orElseThrow();

        assertEquals(
                second,
                selected
        );

        assertNotEquals(
                first,
                selected
        );
    }

    @Test
    void shouldReturnEmptyWhenAllKnownAddressesAreExcluded()
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

        assertTrue(
                selector.select(
                                Set.of(
                                        first,
                                        second
                                )
                        )
                        .isEmpty()
        );
    }

    @Test
    void shouldReturnEmptyWhenNoKnownAddressesExist() {

        PeerAddressManager manager =
                new PeerAddressManager();

        OutboundPeerSelector selector =
                new OutboundPeerSelector(
                        manager
                );

        assertTrue(
                selector.select()
                        .isEmpty()
        );
    }
}
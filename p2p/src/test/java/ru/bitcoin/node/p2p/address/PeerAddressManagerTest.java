package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PeerAddressManagerTest {

    @Test
    void shouldAddAndFindPeerAddress()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        Instant seenAt =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        KnownPeerAddress known =
                manager.add(
                        address,
                        seenAt
                );

        assertEquals(
                1,
                manager.size()
        );

        assertSame(
                known,
                manager.find(address)
                        .orElseThrow()
        );

        assertEquals(
                address,
                known.peerAddress()
        );

        assertEquals(
                seenAt,
                known.firstSeen()
        );

        assertEquals(
                seenAt,
                known.lastSeen()
        );

        assertEquals(
                0,
                known.attempts()
        );

        assertTrue(
                known.lastAttempt()
                        .isEmpty()
        );

        assertTrue(
                known.lastSuccess()
                        .isEmpty()
        );
    }

    @Test
    void shouldDeduplicateByEndpointAndUpdateServices()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        Instant firstSeen =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        Instant secondSeen =
                firstSeen.plusSeconds(
                        60
                );

        PeerAddress original =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        PeerAddress updated =
                peer(
                        "127.0.0.1",
                        8333,
                        9L
                );

        KnownPeerAddress first =
                manager.add(
                        original,
                        firstSeen
                );

        KnownPeerAddress second =
                manager.add(
                        updated,
                        secondSeen
                );

        assertSame(
                first,
                second
        );

        assertEquals(
                1,
                manager.size()
        );

        assertEquals(
                firstSeen,
                second.firstSeen()
        );

        assertEquals(
                secondSeen,
                second.lastSeen()
        );

        assertEquals(
                9L,
                second.peerAddress()
                        .services()
        );
    }

    @Test
    void shouldNotMoveLastSeenBackwards()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        Instant latest =
                Instant.ofEpochSecond(
                        1_700_000_100L
                );

        manager.add(
                address,
                latest
        );

        manager.add(
                address,
                latest.minusSeconds(
                        100
                )
        );

        KnownPeerAddress known =
                manager.find(address)
                        .orElseThrow();

        assertEquals(
                latest,
                known.lastSeen()
        );
    }

    @Test
    void shouldTrackConnectionAttempts()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        Instant seenAt =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        manager.add(
                address,
                seenAt
        );

        Instant firstAttempt =
                seenAt.plusSeconds(
                        10
                );

        Instant secondAttempt =
                seenAt.plusSeconds(
                        20
                );

        manager.markAttempt(
                address,
                firstAttempt
        );

        manager.markAttempt(
                address,
                secondAttempt
        );

        KnownPeerAddress known =
                manager.find(address)
                        .orElseThrow();

        assertEquals(
                2,
                known.attempts()
        );

        assertEquals(
                secondAttempt,
                known.lastAttempt()
                        .orElseThrow()
        );
    }

    @Test
    void shouldResetAttemptsAfterSuccessfulConnection()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        Instant seenAt =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        manager.add(
                address,
                seenAt
        );

        manager.markAttempt(
                address,
                seenAt.plusSeconds(
                        10
                )
        );

        manager.markAttempt(
                address,
                seenAt.plusSeconds(
                        20
                )
        );

        Instant success =
                seenAt.plusSeconds(
                        30
                );

        manager.markSuccess(
                address,
                success
        );

        KnownPeerAddress known =
                manager.find(address)
                        .orElseThrow();

        assertEquals(
                0,
                known.attempts()
        );

        assertEquals(
                success,
                known.lastSuccess()
                        .orElseThrow()
        );
    }

    @Test
    void shouldKeepDifferentPortsAsDifferentPeers()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        Instant time =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        manager.add(
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                ),
                time
        );

        manager.add(
                peer(
                        "127.0.0.1",
                        18333,
                        1L
                ),
                time
        );

        assertEquals(
                2,
                manager.size()
        );
    }

    @Test
    void shouldAddBatchWithoutDuplicatingEndpoints()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        Instant time =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        manager.addAll(
                List.of(
                        peer(
                                "127.0.0.1",
                                8333,
                                1L
                        ),
                        peer(
                                "127.0.0.2",
                                8333,
                                1L
                        ),
                        peer(
                                "127.0.0.1",
                                8333,
                                9L
                        )
                ),
                time
        );

        assertEquals(
                2,
                manager.size()
        );
    }

    @Test
    void shouldRejectStateChangeForUnknownPeer()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress unknown =
                peer(
                        "127.0.0.1",
                        8333,
                        1L
                );

        Instant time =
                Instant.ofEpochSecond(
                        1_700_000_000L
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.markAttempt(
                        unknown,
                        time
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.markSuccess(
                        unknown,
                        time
                )
        );
    }

    private static PeerAddress peer(
            String host,
            int port,
            long services
    ) throws Exception {

        return new PeerAddress(
                InetAddress.getByName(
                        host
                ),
                port,
                services
        );
    }
}
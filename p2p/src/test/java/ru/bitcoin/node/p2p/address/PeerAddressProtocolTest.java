package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PeerAddressProtocolTest {

    @Test
    void receivesLegacyIpv4Addr() throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        manager
                );

        AddrEntry entry =
                AddrEntry.fromIp(
                        1_700_000_000L,
                        9L,
                        InetAddress.getByName(
                                "192.0.2.10"
                        ),
                        8333
                );

        /*
         * The addr parsing path itself does not depend
         * on Peer state. Peer is only used for getaddr.
         *
         * Test the wire/model conversion through the
         * package-private test helper below would require
         * exposing implementation details, so here the
         * message is first verified independently.
         */
        AddrMessage decoded =
                BitcoinMessages.decodeAddr(
                        BitcoinMessages.addr(
                                new AddrMessage(
                                        List.of(entry)
                                )
                        )
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                8333,
                decoded.addresses()
                        .get(0)
                        .port()
        );
    }

    @Test
    void managerAcceptsConvertedIpv4Endpoint()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddress address =
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.10"
                        ),
                        8333,
                        9L
                );

        manager.add(
                address,
                Instant.ofEpochSecond(
                        1_700_000_000L
                )
        );

        assertEquals(
                1,
                manager.size()
        );

        KnownPeerAddress known =
                manager.addresses()
                        .get(0);

        assertEquals(
                "192.0.2.10",
                known.peerAddress()
                        .hostAddress()
        );

        assertEquals(
                8333,
                known.peerAddress()
                        .port()
        );

        assertEquals(
                9L,
                known.peerAddress()
                        .services()
        );
    }

    @Test
    void addrv2Ipv4CanRepresentPeerAddress()
            throws Exception {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        AddrV2Network.IPV4.id(),
                        InetAddress.getByName(
                                        "192.0.2.20"
                                )
                                .getAddress(),
                        8333
                );

        assertEquals(
                AddrV2Network.IPV4,
                entry.network()
                        .orElseThrow()
        );

        assertTrue(
                entry.isGossipEligible()
        );

        assertEquals(
                4,
                entry.address().length
        );
    }

    @Test
    void addrv2Ipv6CanRepresentPeerAddress()
            throws Exception {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        AddrV2Network.IPV6.id(),
                        InetAddress.getByName(
                                        "2001:db8::1"
                                )
                                .getAddress(),
                        8333
                );

        assertEquals(
                AddrV2Network.IPV6,
                entry.network()
                        .orElseThrow()
        );

        assertTrue(
                entry.isGossipEligible()
        );

        assertEquals(
                16,
                entry.address().length
        );
    }

    @Test
    void torV2IsNotEligibleForAddressManager() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        AddrV2Network.TORV2.id(),
                        new byte[10],
                        8333
                );

        assertFalse(
                entry.isGossipEligible()
        );
    }

    @Test
    void unknownNetworkIsNotEligibleForAddressManager() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        0x80,
                        new byte[]{
                                1, 2, 3, 4
                        },
                        8333
                );

        assertFalse(
                entry.isKnownNetwork()
        );

        assertFalse(
                entry.isGossipEligible()
        );
    }

    @Test
    void receivesLegacyAddrThroughProtocol()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        manager
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(
                             0,
                             1,
                             InetAddress.getByName(
                                     "127.0.0.1"
                             )
                     );

             PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest()
                     );

             Peer peer =
                     new Peer(
                             connection,
                             1L,
                             0,
                             true
                     )) {

            peer.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort()
            );

            try (Socket accepted =
                         serverSocket.accept()) {

                AddrEntry entry =
                        AddrEntry.fromIp(
                                1_700_000_000L,
                                9L,
                                InetAddress.getByName(
                                        "192.0.2.10"
                                ),
                                8333
                        );

                protocol.onMessage(
                        peer,
                        BitcoinMessages.addr(
                                new AddrMessage(
                                        List.of(
                                                entry
                                        )
                                )
                        )
                );

                assertEquals(
                        1,
                        manager.size()
                );

                KnownPeerAddress known =
                        manager.addresses()
                                .get(0);

                assertEquals(
                        "192.0.2.10",
                        known.peerAddress()
                                .hostAddress()
                );

                assertEquals(
                        8333,
                        known.peerAddress()
                                .port()
                );

                assertEquals(
                        9L,
                        known.peerAddress()
                                .services()
                );

                /*
                 * Critical source-aware AddrMan assertion:
                 *
                 * 192.0.2.10 is the advertised endpoint,
                 * while 127.0.0.1 is the peer that supplied it.
                 */
                assertEquals(
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        known.source()
                                .address()
                );
            }
        }
    }

    @Test
    void receivesAddrV2Ipv4ThroughProtocol()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        manager
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(
                             0,
                             1,
                             InetAddress.getByName(
                                     "127.0.0.1"
                             )
                     );

             PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest()
                     );

             Peer peer =
                     new Peer(
                             connection,
                             1L,
                             0,
                             true
                     )) {

            peer.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort()
            );

            try (Socket accepted =
                         serverSocket.accept()) {

                AddrV2Entry entry =
                        new AddrV2Entry(
                                1_700_000_000L,
                                9L,
                                AddrV2Network.IPV4.id(),
                                InetAddress.getByName(
                                                "192.0.2.20"
                                        )
                                        .getAddress(),
                                8333
                        );

                protocol.onMessage(
                        peer,
                        BitcoinMessages.addrV2(
                                new AddrV2Message(
                                        List.of(
                                                entry
                                        )
                                )
                        )
                );

                assertEquals(
                        1,
                        manager.size()
                );

                KnownPeerAddress known =
                        manager.addresses()
                                .get(0);

                assertEquals(
                        "192.0.2.20",
                        known.peerAddress()
                                .hostAddress()
                );

                assertEquals(
                        8333,
                        known.peerAddress()
                                .port()
                );

                assertEquals(
                        9L,
                        known.peerAddress()
                                .services()
                );

                assertEquals(
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        known.source()
                                .address()
                );
            }
        }
    }

    @Test
    void ignoresUnknownAddrV2NetworkThroughProtocol()
            throws Exception {

        PeerAddressManager manager =
                new PeerAddressManager();

        PeerAddressProtocol protocol =
                new PeerAddressProtocol(
                        manager
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(
                             0,
                             1,
                             InetAddress.getByName(
                                     "127.0.0.1"
                             )
                     );

             PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest()
                     );

             Peer peer =
                     new Peer(
                             connection,
                             1L,
                             0,
                             true
                     )) {

            peer.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort()
            );

            try (Socket accepted =
                         serverSocket.accept()) {

                AddrV2Entry unknown =
                        new AddrV2Entry(
                                1_700_000_000L,
                                9L,
                                0x07,
                                new byte[]{
                                        1, 2, 3, 4, 5
                                },
                                8333
                        );

                AddrV2Entry ipv4 =
                        new AddrV2Entry(
                                1_700_000_001L,
                                9L,
                                AddrV2Network.IPV4.id(),
                                InetAddress.getByName(
                                                "192.0.2.30"
                                        )
                                        .getAddress(),
                                8333
                        );

                protocol.onMessage(
                        peer,
                        BitcoinMessages.addrV2(
                                new AddrV2Message(
                                        List.of(
                                                unknown,
                                                ipv4
                                        )
                                )
                        )
                );

                assertEquals(
                        1,
                        manager.size()
                );

                KnownPeerAddress known =
                        manager.addresses()
                                .get(0);

                assertEquals(
                        "192.0.2.30",
                        known.peerAddress()
                                .hostAddress()
                );

                assertEquals(
                        InetAddress.getByName(
                                "127.0.0.1"
                        ),
                        known.source()
                                .address()
                );
            }
        }
    }
}
package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerManagerTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldAddAndRemovePeers()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            Peer first =
                    newPeer();

            Peer second =
                    newPeer();

            manager.add(
                    first
            );

            manager.add(
                    second
            );

            assertEquals(
                    2,
                    manager.size()
            );

            assertEquals(
                    2,
                    manager.peers().size()
            );

            assertFalse(
                    manager.isEmpty()
            );

            manager.remove(
                    first
            );

            assertEquals(
                    1,
                    manager.size()
            );

            assertEquals(
                    second,
                    manager.peers()
                            .getFirst()
            );
        }
    }

    @Test
    void shouldNotAddSamePeerTwice()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            Peer peer =
                    newPeer();

            manager.add(
                    peer
            );

            manager.add(
                    peer
            );

            assertEquals(
                    1,
                    manager.size()
            );
        }
    }

    @Test
    void shouldReturnImmutableSnapshot()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            Peer peer =
                    newPeer();

            manager.add(
                    peer
            );

            var snapshot =
                    manager.peers();

            manager.remove(
                    peer
            );

            assertEquals(
                    1,
                    snapshot.size()
            );

            assertTrue(
                    manager.isEmpty()
            );
        }
    }

    @Test
    void shouldExcludeDisconnectedPeersFromReadyPeers()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            manager.add(
                    newPeer()
            );

            assertEquals(
                    1,
                    manager.size()
            );

            assertTrue(
                    manager.readyPeers()
                            .isEmpty()
            );
        }
    }

    @Test
    void shouldRemovePeerWhenPeerCloses()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            Peer peer =
                    newPeer();

            manager.add(
                    peer
            );

            assertEquals(
                    1,
                    manager.size()
            );

            peer.close();

            assertEquals(
                    0,
                    manager.size()
            );

            assertTrue(
                    manager.isEmpty()
            );
        }
    }

    @Test
    void shouldNotRetainPeerThatWasAlreadyClosed()
            throws Exception {

        try (PeerManager manager =
                     new PeerManager()) {

            Peer peer =
                    newPeer();

            peer.close();

            manager.add(
                    peer
            );

            assertTrue(
                    manager.isEmpty()
            );

            assertEquals(
                    0,
                    manager.size()
            );
        }
    }


    @Test
    void shouldTrackExplicitConnectionRole()
            throws Exception {
        try (PeerManager manager = new PeerManager()) {
            Peer peer = newPeer();
            manager.add(peer, PeerConnectionRole.BLOCK_RELAY_ONLY);
            assertEquals(PeerConnectionRole.BLOCK_RELAY_ONLY, manager.roleOf(peer));
            assertTrue(manager.hasRole(peer, PeerConnectionRole.BLOCK_RELAY_ONLY));
            assertFalse(manager.hasRole(peer, PeerConnectionRole.FULL_RELAY));
        }
    }

    @Test
    void protocolCloseRetainsEndpointAndRemovesPeerBeforeDiscouragement() throws Exception {
        try (var server = new java.net.ServerSocket(0);
             var client = new java.net.Socket("127.0.0.1", server.getLocalPort());
             var connection = new PeerConnection(PARAMETERS);
             var manager = new PeerManager()) {
            connection.accept(server.accept());
            var endpoint = connection.remoteAddress();
            var peer = new Peer(connection, 0, 0, true);
            manager.add(peer);
            var snapshot = manager.managedPeers();
            peer.disconnectForProtocolViolation("Invalid message", null);
            assertTrue(manager.isEmpty());
            assertEquals(endpoint, peer.remoteAddress());
            assertEquals(PeerConnectionRole.FULL_RELAY, snapshot.getFirst().role());
            assertTrue(manager.discouragementManager().isDiscouraged(endpoint.getAddress()));
        }
    }

    @Test
    void localOverloadClosesPeerWithoutDiscouragingItsAddress() throws Exception {
        try (var server = new java.net.ServerSocket(0);
             var client = new java.net.Socket("127.0.0.1", server.getLocalPort());
             var connection = new PeerConnection(PARAMETERS);
             var manager = new PeerManager()) {
            connection.accept(server.accept());
            var peer = new Peer(connection, 0, 0, true);
            manager.add(peer);
            peer.close(new PeerCloseException(PeerCloseReason.LOCAL_RESOURCE_LIMIT, "relay", "Local overload"));
            assertTrue(manager.isEmpty());
            assertFalse(manager.discouragementManager().isDiscouraged(peer.remoteAddress().getAddress()));
        }
    }

    private static Peer newPeer() {

        PeerConnection connection =
                new PeerConnection(
                        PARAMETERS
                );

        return new Peer(
                connection,
                0,
                0,
                true
        );
    }
}
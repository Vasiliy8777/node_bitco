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

            manager.add(first);
            manager.add(second);

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

            manager.remove(first);

            assertEquals(
                    1,
                    manager.size()
            );

            assertEquals(
                    second,
                    manager.peers().get(0)
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

            manager.add(peer);
            manager.add(peer);

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

            manager.add(peer);

            var snapshot =
                    manager.peers();

            manager.remove(peer);

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
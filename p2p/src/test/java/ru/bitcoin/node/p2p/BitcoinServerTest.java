package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BitcoinServerTest {
    @Test void discouragedPeersAreAcceptedOnlyWhileCapacityIsSpare() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var manager = new PeerManager(); var server = new BitcoinServer(parameters, manager, 9, true, 3)) {
            manager.discouragementManager().discourage(java.net.InetAddress.getByName("127.0.0.1"));
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            try (var first = client.connect("127.0.0.1", server.localPort(), 0);
                 var second = client.connect("127.0.0.1", server.localPort(), 0)) {
                waitUntil(() -> manager.size() == 2, Duration.ofSeconds(5));
                assertThrows(java.io.IOException.class, () -> client.connect("127.0.0.1", server.localPort(), 0));
                assertTrue(first.isReady()); assertTrue(second.isReady());
            }
        }
    }

    @Test void closingDuringHandshakesReleasesEveryReservedSlot() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var sockets = new java.util.ArrayList<Socket>();
        try (var manager = new PeerManager(); var server = new BitcoinServer(parameters, manager, 9, true, 40)) {
            server.start(0, 0);
            try {
                for (int i = 0; i < 50; i++) sockets.add(new Socket("127.0.0.1", server.localPort()));
                waitUntil(() -> server.inboundPeerCount() > 0, Duration.ofSeconds(5));
                server.close();
                waitUntil(() -> server.inboundPeerCount() == 0, Duration.ofSeconds(5));
                assertTrue(manager.isEmpty());
            } finally { for (var socket : sockets) socket.close(); }
        }
    }
    @Test void fullInboundPoolEvictsACandidateAndKeepsItsBoundedSize() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var clients = new java.util.ArrayList<Peer>();
        try (var manager = new PeerManager(); var server = new BitcoinServer(parameters, manager, 9, true, 40)) {
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            try {
                for (int i = 0; i < 40; i++) clients.add(client.connect("127.0.0.1", server.localPort(), 0));
                waitUntil(() -> manager.size() == 40, Duration.ofSeconds(5));
                try (var replacement = client.connect("127.0.0.1", server.localPort(), 0)) {
                    waitUntil(() -> manager.size() == 40 && clients.stream().anyMatch(peer -> peer.state() == PeerState.CLOSED), Duration.ofSeconds(5));
                    assertTrue(replacement.isReady());
                    assertEquals(40, server.inboundPeerCount());
                }
            } finally { for (var peer : clients) peer.close(); }
            waitUntil(() -> server.inboundPeerCount() == 0, Duration.ofSeconds(5));
        }
    }

    @Test void fullRelayEvictionNeverSelectsReservedBlockOnlyConnections() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        var relays = new java.util.ArrayList<Peer>();
        var blocks = new java.util.ArrayList<Peer>();
        try (var manager = new PeerManager();
             var server = new BitcoinServer(parameters, manager, 9, true, 80, manager.discouragementManager(), false, 50)) {
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            var blockClient = new BitcoinClient(parameters, 9, false);
            try {
                for (int i = 0; i < 40; i++) relays.add(client.connect("127.0.0.1", server.localPort(), 0));
                for (int i = 0; i < 10; i++) blocks.add(blockClient.connect("127.0.0.1", server.localPort(), 0));
                waitUntil(() -> manager.size() == 50, Duration.ofSeconds(5));
                try (var replacement = client.connect("127.0.0.1", server.localPort(), 0)) {
                    waitUntil(() -> manager.size() == 50 && relays.stream().anyMatch(peer -> peer.state() == PeerState.CLOSED), Duration.ofSeconds(5));
                    assertTrue(replacement.isReady());
                    assertTrue(blocks.stream().allMatch(Peer::isReady));
                    assertEquals(50, server.inboundPeerCount());
                }
            } finally {
                for (var peer : relays) peer.close();
                for (var peer : blocks) peer.close();
            }
        }
    }
    @Test void inboundRelayCapReservesRoomForBlockOnlyPeersAndReleasesSlots() throws Exception {
        var parameters = NetworkParametersRegistry.regtest();
        try (var manager = new PeerManager();
             var server = new BitcoinServer(parameters, manager, 9, true, 2,
                     manager.discouragementManager(), false, 50)) {
            server.start(0, 0);
            var client = new BitcoinClient(parameters);
            try (var first = client.connect("127.0.0.1", server.localPort(), 0)) {
                waitUntil(() -> manager.size() == 1, Duration.ofSeconds(5));
                try (var excess = client.connect("127.0.0.1", server.localPort(), 0)) {
                    waitUntil(() -> excess.state() == PeerState.CLOSED, Duration.ofSeconds(5));
                    assertEquals(1, manager.size());
                }
                var blockClient = new BitcoinClient(parameters, 9, false);
                try (var blockOnly = blockClient.connect("127.0.0.1", server.localPort(), 0)) {
                    waitUntil(() -> manager.size() == 2, Duration.ofSeconds(5));
                    assertTrue(blockOnly.isReady());
                    first.close();
                    waitUntil(() -> manager.size() == 1, Duration.ofSeconds(5));
                    try (var replacement = client.connect("127.0.0.1", server.localPort(), 0)) {
                        waitUntil(() -> manager.size() == 2, Duration.ofSeconds(5));
                        assertTrue(replacement.isReady());
                    }
                }
            }
        }
    }

    @Test
    void shouldStartAndListenOnEphemeralPort()
            throws Exception {

        try (PeerManager peerManager =
                     new PeerManager();
             BitcoinServer server =
                     new BitcoinServer(
                             NetworkParametersRegistry.regtest(),
                             peerManager
                     )) {

            server.start(
                    0,
                    0
            );

            assertTrue(
                    server.isRunning()
            );

            assertTrue(
                    server.localPort() > 0
            );
        }
    }

    @Test
    void shouldAcceptAndHandshakeInboundPeer()
            throws Exception {

        try (PeerManager serverPeerManager =
                     new PeerManager();
             BitcoinServer server =
                     new BitcoinServer(
                             NetworkParametersRegistry.regtest(),
                             serverPeerManager
                     )) {

            server.start(
                    0,
                    0
            );

            BitcoinClient client =
                    new BitcoinClient(
                            NetworkParametersRegistry.regtest()
                    );

            try (Peer outboundPeer =
                         client.connect(
                                 "127.0.0.1",
                                 server.localPort(),
                                 0
                         )) {

                waitUntil(
                        () -> serverPeerManager.size() == 1,
                        Duration.ofSeconds(5)
                );

                assertTrue(
                        outboundPeer.isReady()
                );

                assertEquals(
                        1,
                        serverPeerManager.size()
                );

                Peer inboundPeer =
                        serverPeerManager
                                .peers()
                                .getFirst();

                assertTrue(
                        inboundPeer.isReady()
                );

                assertEquals(
                        1,
                        server.inboundPeerCount()
                );
            }

            waitUntil(
                    () -> serverPeerManager.isEmpty(),
                    Duration.ofSeconds(5)
            );
        }
    }

    @Test
    void shouldNotRegisterClientThatDoesNotCompleteHandshake()
            throws Exception {

        try (PeerManager peerManager =
                     new PeerManager();
             BitcoinServer server =
                     new BitcoinServer(
                             NetworkParametersRegistry.regtest(),
                             peerManager
                     )) {

            server.start(
                    0,
                    0
            );

            try (Socket socket =
                         new Socket(
                                 "127.0.0.1",
                                 server.localPort()
                         )) {

                waitUntil(
                        () -> server.inboundPeerCount() == 1,
                        Duration.ofSeconds(5)
                );

                assertTrue(
                        peerManager.isEmpty()
                );
            }
        }
    }

    @Test
    void shouldStopAcceptLoopWhenClosed()
            throws Exception {

        PeerManager peerManager =
                new PeerManager();

        BitcoinServer server =
                new BitcoinServer(
                        NetworkParametersRegistry.regtest(),
                        peerManager
                );

        try {

            server.start(
                    0,
                    0
            );

            int port =
                    server.localPort();

            server.close();

            assertFalse(
                    server.isRunning()
            );

            assertThrows(
                    Exception.class,
                    () -> {
                        try (Socket ignored =
                                     new Socket(
                                             "127.0.0.1",
                                             port
                                     )) {
                            // Must not connect.
                        }
                    }
            );

        } finally {

            server.close();
            peerManager.close();
        }
    }

    @Test
    void shouldRejectSecondStart()
            throws Exception {

        try (PeerManager peerManager =
                     new PeerManager();
             BitcoinServer server =
                     new BitcoinServer(
                             NetworkParametersRegistry.regtest(),
                             peerManager
                     )) {

            server.start(
                    0,
                    0
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> server.start(
                            0,
                            0
                    )
            );
        }
    }

    private static void waitUntil(
            java.util.function.BooleanSupplier condition,
            Duration timeout
    ) throws Exception {

        long deadline =
                System.nanoTime()
                        + timeout.toNanos();

        while (!condition.getAsBoolean()) {

            if (System.nanoTime() >= deadline) {

                fail(
                        "Condition was not satisfied within "
                                + timeout
                );
            }

            Thread.sleep(
                    10
            );
        }
    }
}

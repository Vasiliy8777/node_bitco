package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BitcoinServerTest {

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
package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OutboundPeerSupervisorTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.mainnet();

    @Test
    void shouldReconnectAfterOutboundPeerDisconnects()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            AtomicInteger acceptedConnections =
                    new AtomicInteger();

            /*
             * The server must not close the first connection
             * until the supervisor has actually been started.
             *
             * Otherwise PeerMessageReader can observe EOF
             * between connectOneWithAddress() and
             * supervisor.start(), making the test racy.
             */
            CompletableFuture<Void> allowFirstDisconnect =
                    new CompletableFuture<>();

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runReconnectServer(
                                    serverSocket,
                                    acceptedConnections,
                                    allowFirstDisconnect
                            )
                    );

            PeerAddress address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            serverSocket.getLocalPort(),
                            0L
                    );

            PeerAddressManager addressManager =
                    new PeerAddressManager();

            addressManager.add(
                    address,
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            PeerManager peerManager =
                    new PeerManager();

            OutboundPeerManager outboundPeerManager =
                    new OutboundPeerManager(
                            new BitcoinClient(
                                    PARAMETERS
                            ),
                            peerManager,
                            addressManager
                    );

            OutboundPeerConnection initialConnection =
                    outboundPeerManager
                            .connectOneWithAddress(
                                    100,
                                    List.of()
                            );

            assertTrue(
                    initialConnection.peer()
                            .isReady()
            );

            assertEquals(
                    1,
                    peerManager.size()
            );

            OutboundPeerSupervisor supervisor =
                    new OutboundPeerSupervisor(
                            outboundPeerManager,
                            () -> 101,
                            Duration.ofMillis(
                                    25
                            ),
                            Duration.ofMillis(
                                    100
                            )
                    );

            try {

                /*
                 * Install the close listener FIRST.
                 */
                supervisor.start(
                        initialConnection
                );

                assertTrue(
                        supervisor.hasActiveConnection()
                );

                /*
                 * Only now may the test server disconnect
                 * the initial peer.
                 */
                allowFirstDisconnect.complete(
                        null
                );

                /*
                 * PeerMessageReader must detect EOF,
                 * Peer must become CLOSED,
                 * PeerManager must remove it,
                 * and supervisor must establish connection #2.
                 */
                waitUntil(
                        () ->
                                acceptedConnections.get()
                                        >= 2,
                        Duration.ofSeconds(
                                5
                        )
                );

                waitUntil(
                        supervisor::hasActiveConnection,
                        Duration.ofSeconds(
                                5
                        )
                );

                assertEquals(
                        2,
                        acceptedConnections.get()
                );

                assertEquals(
                        1,
                        peerManager.size()
                );

                OutboundPeerConnection replacement =
                        supervisor.connection();

                assertNotNull(
                        replacement
                );

                assertNotSame(
                        initialConnection.peer(),
                        replacement.peer()
                );

                assertFalse(
                        initialConnection.peer()
                                .isReady()
                );

                assertTrue(
                        replacement.peer()
                                .isReady()
                );

                assertSame(
                        replacement.peer(),
                        peerManager.readyPeers()
                                .get(0)
                );

            } finally {

                /*
                 * Never leave the test server blocked if
                 * an assertion before complete() fails.
                 */
                allowFirstDisconnect.complete(
                        null
                );

                supervisor.close();
                peerManager.close();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldStopRetryingWhenSupervisorIsClosed()
            throws Exception {

        PeerAddress address;

        /*
         * Obtain a currently unused local port.
         */
        try (ServerSocket socket =
                     new ServerSocket(0)) {

            address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            socket.getLocalPort(),
                            0L
                    );
        }

        PeerAddressManager addressManager =
                new PeerAddressManager();

        addressManager.add(
                address,
                Instant.ofEpochSecond(
                        1_700_000_000L
                )
        );

        PeerManager peerManager =
                new PeerManager();

        /*
         * We need a real initial READY peer before the
         * reconnect target disappears.
         */
        try (ServerSocket initialServer =
                     new ServerSocket(0)) {

            PeerAddress initialAddress =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127.0.0.1"
                            ),
                            initialServer.getLocalPort(),
                            0L
                    );

            PeerAddressManager initialAddressManager =
                    new PeerAddressManager();

            initialAddressManager.add(
                    initialAddress,
                    Instant.ofEpochSecond(
                            1_700_000_000L
                    )
            );

            OutboundPeerManager initialOutbound =
                    new OutboundPeerManager(
                            new BitcoinClient(
                                    PARAMETERS
                            ),
                            peerManager,
                            initialAddressManager
                    );

            CompletableFuture<Void> initialServerFuture =
                    CompletableFuture.runAsync(
                            () -> {

                                try (Socket socket =
                                             initialServer.accept()) {

                                    performHandshake(
                                            socket,
                                            100
                                    );

                                    /*
                                     * Close immediately so the
                                     * supervisor observes EOF.
                                     */
                                } catch (Exception exception) {

                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            OutboundPeerConnection initialConnection =
                    initialOutbound
                            .connectOneWithAddress(
                                    100,
                                    List.of()
                            );

            /*
             * Reconnect manager deliberately points at
             * a port where no server is listening.
             */
            OutboundPeerManager reconnectOutbound =
                    new OutboundPeerManager(
                            new BitcoinClient(
                                    PARAMETERS
                            ),
                            peerManager,
                            addressManager
                    );

            OutboundPeerSupervisor supervisor =
                    new OutboundPeerSupervisor(
                            reconnectOutbound,
                            () -> 100,
                            Duration.ofMillis(
                                    200
                            ),
                            Duration.ofMillis(
                                    200
                            )
                    );

            supervisor.start(
                    initialConnection
            );

            initialServerFuture.get(
                    5,
                    TimeUnit.SECONDS
            );

            waitUntil(
                    () -> !initialConnection
                            .peer()
                            .isReady(),
                    Duration.ofSeconds(
                            5
                    )
            );

            /*
             * Allow the immediate reconnect attempt to occur.
             */
            waitUntil(
                    () ->
                            addressManager
                                    .find(address)
                                    .orElseThrow()
                                    .attempts()
                                    >= 1,
                    Duration.ofSeconds(
                            5
                    )
            );

            supervisor.close();

            int attemptsAfterClose =
                    addressManager
                            .find(address)
                            .orElseThrow()
                            .attempts();

            Thread.sleep(
                    500
            );

            assertEquals(
                    attemptsAfterClose,
                    addressManager
                            .find(address)
                            .orElseThrow()
                            .attempts()
            );

            assertTrue(
                    supervisor.isStopping()
            );

            peerManager.close();
        }
    }

    @Test
    void shouldFillMultipleOutboundSlotsWithoutDuplicateAddresses()
            throws Exception {

        try (ServerSocket firstServerSocket = new ServerSocket(0);
             ServerSocket secondServerSocket = new ServerSocket(0)) {

            CompletableFuture<Socket> firstAccepted =
                    CompletableFuture.supplyAsync(
                            () -> acceptAndHandshakeOnce(firstServerSocket)
                    );

            CompletableFuture<Socket> secondAccepted =
                    CompletableFuture.supplyAsync(
                            () -> acceptAndHandshakeOnce(secondServerSocket)
                    );

            PeerAddress firstAddress =
                    new PeerAddress(
                            InetAddress.getByName("127.0.0.1"),
                            firstServerSocket.getLocalPort(),
                            0L
                    );

            PeerAddress secondAddress =
                    new PeerAddress(
                            InetAddress.getByName("127.0.0.1"),
                            secondServerSocket.getLocalPort(),
                            0L
                    );

            PeerAddressManager addressManager =
                    new PeerAddressManager();

            Instant now =
                    Instant.ofEpochSecond(1_700_000_000L);

            addressManager.add(firstAddress, now);
            addressManager.add(secondAddress, now);

            PeerManager peerManager =
                    new PeerManager();

            OutboundPeerManager outboundPeerManager =
                    new OutboundPeerManager(
                            new BitcoinClient(PARAMETERS),
                            peerManager,
                            addressManager
                    );

            OutboundPeerConnection initialConnection =
                    outboundPeerManager.connectOneWithAddress(
                            100,
                            List.of()
                    );

            OutboundPeerSupervisor supervisor =
                    new OutboundPeerSupervisor(
                            outboundPeerManager,
                            () -> 101,
                            2,
                            Duration.ofMillis(25),
                            Duration.ofMillis(100)
                    );

            try {
                supervisor.start(initialConnection);

                waitUntil(
                        () -> supervisor.activeConnectionCount() == 2,
                        Duration.ofSeconds(5)
                );

                assertEquals(2, supervisor.targetOutboundPeers());
                assertEquals(2, supervisor.connections().size());
                assertEquals(2, peerManager.readyPeers().size());

                assertEquals(
                        2L,
                        supervisor.connections()
                                .stream()
                                .map(OutboundPeerConnection::address)
                                .distinct()
                                .count()
                );

                /*
                 * Exactly one server accepted the initial connection at
                 * height 100 and the other accepted the automatically filled
                 * slot at height 101. Which address is selected first is not
                 * part of the contract.
                 */
                Socket firstSocket = firstAccepted.get(5, TimeUnit.SECONDS);
                Socket secondSocket = secondAccepted.get(5, TimeUnit.SECONDS);

                assertFalse(firstSocket.isClosed());
                assertFalse(secondSocket.isClosed());

            } finally {
                supervisor.close();
                peerManager.close();
            }
        }
    }

    private static Socket acceptAndHandshakeOnce(
            ServerSocket serverSocket
    ) {
        try {
            Socket socket = serverSocket.accept();
            socket.setSoTimeout(5_000);

            BufferedInputStream input =
                    new BufferedInputStream(socket.getInputStream());

            BufferedOutputStream output =
                    new BufferedOutputStream(socket.getOutputStream());

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(PARAMETERS)
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(PARAMETERS);

            BitcoinMessage version =
                    reader.read(input).orElseThrow();

            assertEquals("version", version.command());

            int startHeight =
                    BitcoinMessages.decodeVersion(version).startHeight();

            assertTrue(
                    startHeight == 100 || startHeight == 101,
                    "unexpected start height: " + startHeight
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            0x123456789ABCDEFL,
                            "/outbound-supervisor-multi-test/",
                            321,
                            true
                    );

            output.write(encoder.encode(BitcoinMessages.version(remoteVersion)));
            output.flush();

            assertEquals("wtxidrelay", reader.read(input).orElseThrow().command());
            assertEquals("sendaddrv2", reader.read(input).orElseThrow().command());
            assertEquals("verack", reader.read(input).orElseThrow().command());

            output.write(encoder.encode(BitcoinMessages.wtxidRelay()));
            output.write(encoder.encode(BitcoinMessages.sendAddrV2()));
            output.write(encoder.encode(BitcoinMessages.verack()));
            output.flush();

            return socket;

        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void runReconnectServer(
            ServerSocket serverSocket,
            AtomicInteger acceptedConnections,
            CompletableFuture<Void> allowFirstDisconnect
    ) {

        try {

            /*
             * Connection #1:
             *
             * Complete the handshake, but deliberately keep
             * the socket open until the test confirms that
             * OutboundPeerSupervisor has been started.
             */
            try (Socket first =
                         serverSocket.accept()) {

                acceptedConnections
                        .incrementAndGet();

                performHandshake(
                        first,
                        100
                );

                /*
                 * Wait until supervisor.start() has installed
                 * its close listener.
                 */
                allowFirstDisconnect.get(
                        5,
                        TimeUnit.SECONDS
                );
            }

            /*
             * Leaving the try-with-resources above closes
             * connection #1.
             *
             * PeerMessageReader should now observe EOF and
             * trigger the reconnect.
             */

            /*
             * Connection #2:
             * supervisor reconnect.
             */
            try (Socket second =
                         serverSocket.accept()) {

                acceptedConnections
                        .incrementAndGet();

                BufferedInputStream input =
                        performHandshake(
                                second,
                                101
                        );

                /*
                 * Keep replacement peer alive until
                 * PeerManager.close() closes it.
                 */
                assertEquals(
                        -1,
                        input.read()
                );
            }

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static BufferedInputStream performHandshake(
            Socket socket,
            int expectedStartHeight
    ) throws Exception {

        socket.setSoTimeout(
                5_000
        );

        BufferedInputStream input =
                new BufferedInputStream(
                        socket.getInputStream()
                );

        BufferedOutputStream output =
                new BufferedOutputStream(
                        socket.getOutputStream()
                );

        BitcoinMessageStreamReader reader =
                new BitcoinMessageStreamReader(
                        new BitcoinMessageDecoder(
                                PARAMETERS
                        )
                );

        BitcoinMessageEncoder encoder =
                new BitcoinMessageEncoder(
                        PARAMETERS
                );

        BitcoinMessage version =
                reader.read(
                        input
                ).orElseThrow();

        assertEquals(
                "version",
                version.command()
        );

        VersionMessage decoded =
                BitcoinMessages.decodeVersion(
                        version
                );

        assertEquals(
                expectedStartHeight,
                decoded.startHeight()
        );

        VersionMessage remoteVersion =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        0x123456789ABCDEFL,
                        "/outbound-supervisor-test/",
                        321,
                        true
                );

        output.write(
                encoder.encode(
                        BitcoinMessages.version(
                                remoteVersion
                        )
                )
        );

        output.flush();

        assertEquals(
                "wtxidrelay",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "sendaddrv2",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "verack",
                reader.read(input)
                        .orElseThrow()
                        .command()
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.wtxidRelay()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.sendAddrV2()
                )
        );

        output.write(
                encoder.encode(
                        BitcoinMessages.verack()
                )
        );

        output.flush();

        return input;
    }

    private static void waitUntil(
            Condition condition,
            Duration timeout
    ) throws Exception {

        long deadline =
                System.nanoTime()
                        + timeout.toNanos();

        while (!condition.evaluate()) {

            if (System.nanoTime()
                    >= deadline) {

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

    @FunctionalInterface
    private interface Condition {

        boolean evaluate()
                throws Exception;
    }
}
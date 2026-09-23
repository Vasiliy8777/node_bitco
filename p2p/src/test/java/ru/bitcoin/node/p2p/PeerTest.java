package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PeerTest {

    private static final long LOCAL_NONCE =
            0x0102030405060708L;

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    @Test
    void shouldCompleteOutboundHandshake()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSuccessfulPeer(
                                    serverSocket
                            )
                    );

            try (PeerConnection connection =
                         connection();
                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 100,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertEquals(
                        PeerState.CONNECTED,
                        peer.state()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertTrue(
                        peer.localWtxidRelaySent()
                );

                assertTrue(
                        peer.localSendAddrV2Sent()
                );

                assertTrue(
                        peer.remoteWtxidRelay()
                );

                assertTrue(
                        peer.remoteWantsAddrV2()
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                assertEquals(
                        REMOTE_NONCE,
                        peer.remoteVersion()
                                .nonce()
                );

                assertEquals(
                        "/test-peer/",
                        peer.remoteVersion()
                                .userAgent()
                );

                assertEquals(
                        123,
                        peer.remoteVersion()
                                .startHeight()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldNotSendModernNegotiationToOldPeer()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(socket);

                                    BitcoinMessage ourVersion =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "version",
                                            ourVersion.command()
                                    );

                                    VersionMessage oldVersion =
                                            new VersionMessage(
                                                    70015,
                                                    VersionMessage
                                                            .DEFAULT_SERVICES,
                                                    1_700_000_000L,
                                                    NetworkAddress
                                                            .unspecified(),
                                                    NetworkAddress
                                                            .unspecified(),
                                                    REMOTE_NONCE,
                                                    "/old-peer/",
                                                    100,
                                                    true
                                            );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.version(
                                                            oldVersion
                                                    )
                                            )
                                    );

                                    io.output().flush();

                                    BitcoinMessage next =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "verack",
                                            next.command()
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.verack()
                                            )
                                    );

                                    io.output().flush();

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();
                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertFalse(
                        peer.localWtxidRelaySent()
                );

                assertFalse(
                        peer.localSendAddrV2Sent()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldRejectVerackBeforeVersion()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(socket);

                                    io.reader()
                                            .read(io.input())
                                            .orElseThrow();

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages
                                                            .verack()
                                            )
                                    );

                                    io.output().flush();

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();
                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertThrows(
                        java.io.IOException.class,
                        peer::handshake
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldRejectSelfConnection()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(socket);

                                    io.reader()
                                            .read(io.input())
                                            .orElseThrow();

                                    sendVersion(
                                            io,
                                            LOCAL_NONCE
                                    );

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();
                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertThrows(
                        java.io.IOException.class,
                        peer::handshake
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldRejectDuplicateVersion()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(socket);

                                    io.reader()
                                            .read(io.input())
                                            .orElseThrow();

                                    sendVersion(
                                            io,
                                            REMOTE_NONCE
                                    );

                                    /*
                                     * Read our verack before sending
                                     * duplicate version.
                                     */
                                    io.reader()
                                            .read(io.input())
                                            .orElseThrow();

                                    sendVersion(
                                            io,
                                            REMOTE_NONCE + 1
                                    );

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();
                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertThrows(
                        java.io.IOException.class,
                        peer::handshake
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runSuccessfulPeer(
            ServerSocket serverSocket
    ) {
        try (Socket socket =
                     serverSocket.accept()) {

            PeerIo io =
                    peerIo(socket);

            BitcoinMessage ourVersion =
                    io.reader()
                            .read(io.input())
                            .orElseThrow();

            assertEquals(
                    "version",
                    ourVersion.command()
            );

            VersionMessage decoded =
                    BitcoinMessages.decodeVersion(
                            ourVersion
                    );

            assertEquals(
                    LOCAL_NONCE,
                    decoded.nonce()
            );

            sendVersion(
                    io,
                    REMOTE_NONCE
            );

            BitcoinMessage wtxidRelay =
                    io.reader()
                            .read(io.input())
                            .orElseThrow();

            assertEquals(
                    "wtxidrelay",
                    wtxidRelay.command()
            );

            assertEquals(
                    0,
                    wtxidRelay.payloadLength()
            );

            BitcoinMessage sendAddrV2 =
                    io.reader()
                            .read(io.input())
                            .orElseThrow();

            assertEquals(
                    "sendaddrv2",
                    sendAddrV2.command()
            );

            assertEquals(
                    0,
                    sendAddrV2.payloadLength()
            );

            BitcoinMessage ourVerack =
                    io.reader()
                            .read(io.input())
                            .orElseThrow();

            assertEquals(
                    "verack",
                    ourVerack.command()
            );

            io.output().write(
                    io.encoder().encode(
                            BitcoinMessages.wtxidRelay()
                    )
            );

            io.output().write(
                    io.encoder().encode(
                            BitcoinMessages.sendAddrV2()
                    )
            );

            io.output().write(
                    io.encoder().encode(
                            BitcoinMessages.verack()
                    )
            );

            io.output().flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Test
    void shouldBecomeClosedWhenClosed()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSuccessfulPeer(
                                    serverSocket
                            )
                    );

            PeerConnection connection =
                    connection();

            Peer peer =
                    new Peer(
                            connection,
                            VersionMessage.DEFAULT_SERVICES,
                            100,
                            true,
                            LOCAL_NONCE
                    );

            try {
                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                assertTrue(
                        connection.isConnected()
                );

                peer.close();

                assertEquals(
                        PeerState.CLOSED,
                        peer.state()
                );

                assertFalse(
                        peer.isReady()
                );

                assertFalse(
                        connection.isConnected()
                );

                /*
                 * Closing an already closed peer must remain safe.
                 */
                peer.close();

                assertEquals(
                        PeerState.CLOSED,
                        peer.state()
                );

            } finally {
                peer.close();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldDispatchPostHandshakePingThroughBackgroundMessageReader()
            throws Exception {

        final long pingNonce =
                0x2122232425262728L;

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(
                                                    socket
                                            );

                                    /*
                                     * Receive client's VERSION.
                                     */
                                    BitcoinMessage ourVersion =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "version",
                                            ourVersion.command()
                                    );

                                    /*
                                     * Send server VERSION.
                                     */
                                    sendVersion(
                                            io,
                                            REMOTE_NONCE
                                    );

                                    /*
                                     * Receive feature negotiation
                                     * and VERACK from client.
                                     */
                                    assertEquals(
                                            "wtxidrelay",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "sendaddrv2",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "verack",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    /*
                                     * Complete server side of handshake.
                                     */
                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.wtxidRelay()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.sendAddrV2()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.verack()
                                            )
                                    );

                                    io.output().flush();

                                    /*
                                     * Send post-handshake PING.
                                     */
                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.ping(
                                                            new PingMessage(
                                                                    pingNonce
                                                            )
                                                    )
                                            )
                                    );

                                    io.output().flush();

                                    /*
                                     * No client-side receive()/dispatch()
                                     * is performed by the test.
                                     *
                                     * Background PeerMessageReader must:
                                     *
                                     * receive PING
                                     * -> dispatch it
                                     * -> Peer.handleMessage()
                                     * -> send PONG.
                                     */
                                    BitcoinMessage pongWire =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "pong",
                                            pongWire.command()
                                    );

                                    assertEquals(
                                            pingNonce,
                                            BitcoinMessages.decodePong(
                                                    pongWire
                                            ).nonce()
                                    );

                                } catch (Exception exception) {
                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                /*
                 * Explicitly switch this peer to
                 * background message reading.
                 */

                /*
                 * IMPORTANT:
                 *
                 * Wait for the server while Peer is still open.
                 * Successful server completion proves that it
                 * received the PONG generated through the
                 * background reader path.
                 *
                 * Only after this get() returns may
                 * try-with-resources close the Peer.
                 */
                server.get(
                        5,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    @Test
    void shouldDispatchPostHandshakePingThroughPeerMessageDispatcher()
            throws Exception {

        final long pingNonce =
                0x2122232425262728L;

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(
                                                    socket
                                            );

                                    BitcoinMessage ourVersion =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "version",
                                            ourVersion.command()
                                    );

                                    sendVersion(
                                            io,
                                            REMOTE_NONCE
                                    );

                                    assertEquals(
                                            "wtxidrelay",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "sendaddrv2",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "verack",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.wtxidRelay()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.sendAddrV2()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.verack()
                                            )
                                    );

                                    io.output().flush();

                                    /*
                                     * Post-handshake PING.
                                     */
                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.ping(
                                                            new PingMessage(
                                                                    pingNonce
                                                            )
                                                    )
                                            )
                                    );

                                    io.output().flush();

                                    BitcoinMessage pongWire =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "pong",
                                            pongWire.command()
                                    );

                                    assertEquals(
                                            pingNonce,
                                            BitcoinMessages.decodePong(
                                                    pongWire
                                            ).nonce()
                                    );

                                } catch (Exception exception) {
                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertTrue(
                        peer.messageReader()
                                .isStarted()
                );

                /*
                 * Keep Peer alive until server has
                 * actually received and validated PONG.
                 */
                server.get(
                        5,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    @Test
    void shouldFailPendingRequestsWhenPeerIsClosed()
            throws Exception {

        PeerConnection connection =
                new PeerConnection(
                        NetworkParametersRegistry.mainnet(),
                        5_000,
                        5_000
                );

        Peer peer =
                new Peer(
                        connection,
                        0L,
                        0,
                        true
                );

        CompletableFuture<Block> blockFuture =
                peer.messageDispatcher()
                        .registerBlock(
                                GenesisBlockFactory.create(
                                        NetworkParametersRegistry.mainnet()
                                ).hash()
                        );

        CompletableFuture<HeadersMessage> headersFuture =
                peer.messageDispatcher()
                        .registerHeaders();

        peer.close();

        assertTrue(
                blockFuture.isCompletedExceptionally()
        );

        assertTrue(
                headersFuture.isCompletedExceptionally()
        );

        CompletionException blockException =
                assertThrows(
                        CompletionException.class,
                        blockFuture::join
                );

        CompletionException headersException =
                assertThrows(
                        CompletionException.class,
                        headersFuture::join
                );

        assertInstanceOf(
                IOException.class,
                blockException.getCause()
        );

        assertInstanceOf(
                IOException.class,
                headersException.getCause()
        );

        assertEquals(
                "Peer closed",
                blockException.getCause()
                        .getMessage()
        );

        assertEquals(
                "Peer closed",
                headersException.getCause()
                        .getMessage()
        );
    }

    @Test
    void shouldOwnSingleMessageDispatcher() {

        try (PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            PeerMessageDispatcher first =
                    peer.messageDispatcher();

            PeerMessageDispatcher second =
                    peer.messageDispatcher();

            assertNotNull(
                    first
            );

            assertSame(
                    first,
                    second
            );
        } catch (Exception exception) {
            fail(
                    exception
            );
        }
    }

    @Test
    void shouldOwnSingleMessageReader() {

        try (PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            PeerMessageReader first =
                    peer.messageReader();

            PeerMessageReader second =
                    peer.messageReader();

            assertNotNull(
                    first
            );

            assertSame(
                    first,
                    second
            );

        } catch (Exception exception) {
            fail(
                    exception
            );
        }
    }

    @Test
    void shouldRejectStartingMessageReaderBeforeHandshake() {

        try (PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> peer.messageReader()
                                    .start()
                    );

            assertEquals(
                    "Peer handshake is not complete",
                    exception.getMessage()
            );

        } catch (IOException exception) {
            fail(
                    exception
            );
        }
    }

    @Test
    void shouldRejectStartingClosedMessageReader() {

        try (PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            PeerMessageReader reader =
                    peer.messageReader();

            reader.close();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            reader::start
                    );

            assertEquals(
                    "Peer message reader is closed",
                    exception.getMessage()
            );

        } catch (IOException exception) {
            fail(
                    exception
            );
        }
    }

    @Test
    void shouldClosePeerWhenBackgroundMessageReaderReachesEof()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(
                                                    socket
                                            );

                                    /*
                                     * Receive client VERSION.
                                     */
                                    BitcoinMessage ourVersion =
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow();

                                    assertEquals(
                                            "version",
                                            ourVersion.command()
                                    );

                                    /*
                                     * Send server VERSION.
                                     */
                                    sendVersion(
                                            io,
                                            REMOTE_NONCE
                                    );

                                    /*
                                     * Receive client's feature
                                     * negotiation and VERACK.
                                     */
                                    assertEquals(
                                            "wtxidrelay",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "sendaddrv2",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    assertEquals(
                                            "verack",
                                            io.reader()
                                                    .read(io.input())
                                                    .orElseThrow()
                                                    .command()
                                    );

                                    /*
                                     * Complete server side
                                     * of the handshake.
                                     */
                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.wtxidRelay()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.sendAddrV2()
                                            )
                                    );

                                    io.output().write(
                                            io.encoder().encode(
                                                    BitcoinMessages.verack()
                                            )
                                    );

                                    io.output().flush();

                                    /*
                                     * Returning from this block closes
                                     * the server-side socket.
                                     *
                                     * Client background reader must
                                     * observe EOF and terminate Peer.
                                     */

                                } catch (Exception exception) {
                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            try (PeerConnection connection =
                         connection();

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                assertTrue(
                        peer.isReady()
                );

                /*
                 * Register requests before the remote peer
                 * disconnects. This proves EOF also fails
                 * outstanding work.
                 */
                CompletableFuture<Block> blockFuture =
                        peer.messageDispatcher()
                                .registerBlock(
                                        GenesisBlockFactory.create(
                                                NetworkParametersRegistry.mainnet()
                                        ).hash()
                                );

                CompletableFuture<HeadersMessage> headersFuture =
                        peer.messageDispatcher()
                                .registerHeaders();


                /*
                 * Ensure the server has completed its work
                 * and closed its socket.
                 */
                server.get(
                        5,
                        TimeUnit.SECONDS
                );

                /*
                 * Reader transition is asynchronous.
                 * Do not assert state immediately after
                 * server.get(): the reader thread may not
                 * have observed EOF yet.
                 */
                long deadline =
                        System.nanoTime()
                                + TimeUnit.SECONDS.toNanos(
                                5
                        );

                while (peer.state()
                        != PeerState.CLOSED
                        && System.nanoTime()
                        < deadline) {

                    Thread.sleep(
                            10
                    );
                }

                assertEquals(
                        PeerState.CLOSED,
                        peer.state()
                );

                assertFalse(
                        peer.isReady()
                );

                assertFalse(
                        connection.isConnected()
                );

                assertTrue(
                        blockFuture.isCompletedExceptionally()
                );

                assertTrue(
                        headersFuture.isCompletedExceptionally()
                );

                assertInstanceOf(
                        IOException.class,
                        assertThrows(
                                CompletionException.class,
                                blockFuture::join
                        ).getCause()
                );

                assertInstanceOf(
                        IOException.class,
                        assertThrows(
                                CompletionException.class,
                                headersFuture::join
                        ).getCause()
                );
            }
        }
    }

    @Test
    void shouldNotifyLateCloseListenerImmediately()
            throws Exception {

        Peer peer =
                new Peer(
                        connection(),
                        0,
                        0,
                        true
                );

        peer.close();

        AtomicInteger notifications =
                new AtomicInteger();

        peer.addCloseListener(
                (closedPeer, cause) -> {

                    assertSame(
                            peer,
                            closedPeer
                    );

                    assertNotNull(
                            cause
                    );

                    notifications.incrementAndGet();
                }
        );

        assertEquals(
                1,
                notifications.get()
        );
    }

    @Test
    void shouldNotifyCloseListenersExactlyOnceWhenCloseRacesReaderFailure()
            throws Exception {

        Peer peer =
                new Peer(
                        connection(),
                        0,
                        0,
                        true
                );

        AtomicInteger notifications =
                new AtomicInteger();

        peer.addCloseListener(
                (closedPeer, cause) ->
                        notifications.incrementAndGet()
        );

        int taskCount =
                32;

        CountDownLatch ready =
                new CountDownLatch(
                        taskCount
                );

        CountDownLatch start =
                new CountDownLatch(
                        1
                );

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        taskCount
                );

        try {

            CompletableFuture<?>[] futures =
                    new CompletableFuture<?>[
                            taskCount
                            ];

            for (int i = 0;
                 i < taskCount;
                 i++) {

                final int index =
                        i;

                futures[i] =
                        CompletableFuture.runAsync(
                                () -> {

                                    ready.countDown();

                                    try {

                                        start.await();

                                        if ((index & 1) == 0) {

                                            peer.close();

                                        } else {

                                            peer.handleReaderFailure(
                                                    new IOException(
                                                            "reader failed"
                                                    )
                                            );
                                        }

                                    } catch (Exception exception) {

                                        throw new RuntimeException(
                                                exception
                                        );
                                    }
                                },
                                executor
                        );
            }

            assertTrue(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

            start.countDown();

            CompletableFuture.allOf(
                    futures
            ).get(
                    5,
                    TimeUnit.SECONDS
            );

        } finally {

            executor.shutdownNow();
        }

        assertEquals(
                PeerState.CLOSED,
                peer.state()
        );

        assertEquals(
                1,
                notifications.get()
        );
    }

    @Test
    void shouldAcceptInboundConnection()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0);
             Socket client =
                     new Socket(
                             "127.0.0.1",
                             serverSocket.getLocalPort()
                     );
             Socket accepted =
                     serverSocket.accept();
             Peer peer =
                     new Peer(
                             new PeerConnection(
                                     NetworkParametersRegistry.regtest()
                             ),
                             0L,
                             0,
                             true
                     )) {

            peer.accept(
                    accepted
            );

            assertEquals(
                    PeerState.CONNECTED,
                    peer.state()
            );
        }
    }

    @Test
    void shouldExposeRemoteAddressAfterOutboundConnect()
            throws Exception {

        InetAddress loopback =
                InetAddress.getByName(
                        "127.0.0.1"
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(
                             0,
                             1,
                             loopback
                     );

             PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            peer.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort()
            );

            try (Socket accepted =
                         serverSocket.accept()) {

                assertEquals(
                        loopback,
                        peer.remoteAddress()
                                .getAddress()
                );

                assertEquals(
                        serverSocket.getLocalPort(),
                        peer.remoteAddress()
                                .getPort()
                );
            }
        }
    }

    @Test
    void shouldExposeRemoteAddressAfterInboundAccept()
            throws Exception {

        InetAddress loopback =
                InetAddress.getByName(
                        "127.0.0.1"
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(
                             0,
                             1,
                             loopback
                     );

             Socket client =
                     new Socket(
                             loopback,
                             serverSocket.getLocalPort()
                     );

             Socket accepted =
                     serverSocket.accept();

             PeerConnection connection =
                     connection();

             Peer peer =
                     new Peer(
                             connection,
                             VersionMessage.DEFAULT_SERVICES,
                             0,
                             true,
                             LOCAL_NONCE
                     )) {

            peer.accept(
                    accepted
            );

            assertEquals(
                    client.getLocalAddress(),
                    peer.remoteAddress()
                            .getAddress()
            );

            assertEquals(
                    client.getLocalPort(),
                    peer.remoteAddress()
                            .getPort()
            );

            assertTrue(
                    peer.isInboundConnection()
            );
        }
    }

    private static void sendVersion(
            PeerIo io,
            long nonce
    ) throws Exception {

        VersionMessage version =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        nonce,
                        "/test-peer/",
                        123,
                        true
                );

        io.output().write(
                io.encoder().encode(
                        BitcoinMessages.version(
                                version
                        )
                )
        );

        io.output().flush();
    }

    private static PeerConnection connection() {
        return new PeerConnection(
                NetworkParametersRegistry.mainnet(),
                5_000,
                5_000
        );
    }

    private static PeerIo peerIo(
            Socket socket
    ) throws Exception {

        socket.setSoTimeout(5_000);

        return new PeerIo(
                new BitcoinMessageStreamReader(
                        new BitcoinMessageDecoder(
                                NetworkParametersRegistry
                                        .mainnet()
                        )
                ),
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry
                                .mainnet()
                ),
                new BufferedInputStream(
                        socket.getInputStream()
                ),
                new BufferedOutputStream(
                        socket.getOutputStream()
                )
        );
    }

    private record PeerIo(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) {
    }
}
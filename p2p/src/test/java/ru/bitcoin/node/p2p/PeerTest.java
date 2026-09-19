package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
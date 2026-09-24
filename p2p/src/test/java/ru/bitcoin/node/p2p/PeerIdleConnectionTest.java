package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerIdleConnectionTest {

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.mainnet();

    private static final long REMOTE_NONCE =
            0x1122334455667788L;

    @Test
    void shouldKeepReadyPeerAliveWhenConnectionIsIdleLongerThanHandshakeTimeout()
            throws Exception {

        /*
         * A deliberately short timeout makes the regression
         * deterministic and keeps the test fast.
         */
        int handshakeReadTimeoutMillis =
                100;

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runIdlePeer(
                                    serverSocket,
                                    handshakeReadTimeoutMillis
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 handshakeReadTimeoutMillis
                         );

                 Peer peer =
                         new Peer(
                                 connection,
                                 VersionMessage.DEFAULT_SERVICES,
                                 0,
                                 true
                         )) {

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                assertTrue(
                        peer.isReady()
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );

                /*
                 * Stay idle substantially longer than the
                 * configured handshake socket timeout.
                 *
                 * Before the fix PeerMessageReader would receive
                 * SocketTimeoutException here and close the peer.
                 */
                Thread.sleep(
                        handshakeReadTimeoutMillis * 5L
                );

                assertTrue(
                        peer.isReady(),
                        "READY peer must not be closed merely because "
                                + "the connection is idle"
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runIdlePeer(
            ServerSocket serverSocket,
            int handshakeReadTimeoutMillis
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            PeerIo io =
                    peerIo(
                            socket
                    );

            BitcoinMessage version =
                    io.reader()
                            .read(
                                    io.input()
                            )
                            .orElseThrow();

            assertEquals(
                    "version",
                    version.command()
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/idle-peer-test/",
                            0,
                            true
                    );

            io.output().write(
                    io.encoder().encode(
                            BitcoinMessages.version(
                                    remoteVersion
                            )
                    )
            );

            io.output().flush();

            BitcoinMessage wtxidRelay =
                    io.reader()
                            .read(
                                    io.input()
                            )
                            .orElseThrow();

            assertEquals(
                    "wtxidrelay",
                    wtxidRelay.command()
            );

            BitcoinMessage sendAddrV2 =
                    io.reader()
                            .read(
                                    io.input()
                            )
                            .orElseThrow();

            assertEquals(
                    "sendaddrv2",
                    sendAddrV2.command()
            );

            BitcoinMessage sendCmpct =
                    io.reader()
                            .read(io.input())
                            .orElseThrow();

            assertEquals(
                    "sendcmpct",
                    sendCmpct.command()
            );

            BitcoinMessage verack =
                    io.reader()
                            .read(
                                    io.input()
                            )
                            .orElseThrow();

            assertEquals(
                    "verack",
                    verack.command()
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
             * Remain completely silent for much longer than
             * the client's handshake read timeout.
             */
            Thread.sleep(
                    handshakeReadTimeoutMillis * 5L
            );

            /*
             * The client closes the socket when the test exits
             * its try-with-resources block. Waiting for EOF also
             * proves that the server did not initiate the close.
             */
            assertEquals(
                    -1,
                    io.input().read()
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static PeerIo peerIo(
            Socket socket
    ) throws Exception {

        return new PeerIo(
                new BitcoinMessageStreamReader(
                        new BitcoinMessageDecoder(
                                PARAMETERS
                        )
                ),
                new BitcoinMessageEncoder(
                        PARAMETERS
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
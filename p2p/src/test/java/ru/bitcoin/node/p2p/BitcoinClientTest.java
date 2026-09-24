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

import static org.junit.jupiter.api.Assertions.*;

class BitcoinClientTest {

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.mainnet();

    @Test
    void shouldConnectAndReturnReadyPeer()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSuccessfulPeer(
                                    serverSocket
                            )
                    );

            BitcoinClient client =
                    new BitcoinClient(
                            PARAMETERS
                    );

            try (Peer peer =
                         client.connect(
                                 "127.0.0.1",
                                 serverSocket.getLocalPort(),
                                 100
                         )) {

                assertTrue(
                        peer.isReady()
                );

                assertEquals(
                        PeerState.READY,
                        peer.state()
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
                        REMOTE_NONCE,
                        peer.remoteVersion()
                                .nonce()
                );

                assertEquals(
                        "/bitcoin-client-test/",
                        peer.remoteVersion()
                                .userAgent()
                );

                assertEquals(
                        321,
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
    void shouldDisableTransactionRelayForBlockRelayOnlyConnection()
            throws Exception {

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Boolean> advertisedRelay = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(5_000);
                    PeerIo io = peerIo(socket);
                    BitcoinMessage version = io.reader().read(io.input()).orElseThrow();
                    VersionMessage localVersion = BitcoinMessages.decodeVersion(version);

                    VersionMessage remoteVersion = new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/bitcoin-client-role-test/",
                            321,
                            true
                    );
                    io.output().write(io.encoder().encode(BitcoinMessages.version(remoteVersion)));
                    io.output().flush();

                    assertEquals("wtxidrelay", io.reader().read(io.input()).orElseThrow().command());
                    assertEquals("sendaddrv2", io.reader().read(io.input()).orElseThrow().command());

                    assertEquals("sendcmpct", io.reader().read(io.input()).orElseThrow().command());
                    assertEquals("verack", io.reader().read(io.input()).orElseThrow().command());
                    io.output().write(io.encoder().encode(BitcoinMessages.wtxidRelay()));
                    io.output().write(io.encoder().encode(BitcoinMessages.sendAddrV2()));
                    io.output().write(io.encoder().encode(BitcoinMessages.verack()));
                    io.output().flush();
                    return localVersion.relay();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            BitcoinClient client = new BitcoinClient(PARAMETERS);
            try (Peer peer = client.connectManaged(
                    "127.0.0.1",
                    serverSocket.getLocalPort(),
                    100,
                    PeerConnectionRole.BLOCK_RELAY_ONLY
            )) {
                assertTrue(peer.isReady());
            }

            assertFalse(advertisedRelay.get(5, TimeUnit.SECONDS));
        }
    }

    private static void runSuccessfulPeer(
            ServerSocket serverSocket
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

            VersionMessage decodedVersion =
                    BitcoinMessages.decodeVersion(
                            version
                    );

            assertEquals(
                    100,
                    decodedVersion.startHeight()
            );

            assertTrue(
                    decodedVersion.relay()
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/bitcoin-client-test/",
                            321,
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
             * Keep the connection alive until the client-side
             * test closes the returned READY peer.
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
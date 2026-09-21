package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.codec.HeadersMessageCodec;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.PingMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HeaderSynchronizerTest {

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    private static final long PING_NONCE =
            0x2122232425262728L;

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldRequestAndReceiveHeadersWhileHandlingPing()
            throws Exception {

        Block genesis =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 locatorHash =
                genesis.hash();

        Hash256 stopHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    locatorHash,
                                    stopHash,
                                    genesis
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
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

                HeaderSynchronizer synchronizer =
                        new HeaderSynchronizer(
                                peer,
                                Duration.ofMillis(
                                        100
                                )
                        );

                HeadersMessage headers =
                        synchronizer.download(
                                List.of(locatorHash),
                                stopHash
                        );



                assertEquals(
                        1,
                        headers.size()
                );

                assertEquals(
                        genesis.header(),
                        headers.headers().get(0)
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldTimeoutWhenPeerDoesNotRespondToGetHeaders()
            throws Exception {

        Block genesis =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 locatorHash =
                genesis.hash();

        Hash256 stopHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runSilentPeer(
                                    serverSocket,
                                    locatorHash,
                                    stopHash
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 PARAMETERS,
                                 5_000,
                                 5_000
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

                Duration timeout =
                        Duration.ofMillis(
                                100
                        );

                HeaderSynchronizer synchronizer =
                        new HeaderSynchronizer(
                                peer,
                                timeout
                        );

                HeaderSynchronizationTimeoutException exception =
                        assertThrows(
                                HeaderSynchronizationTimeoutException.class,
                                () -> synchronizer.download(
                                        List.of(
                                                locatorHash
                                        ),
                                        stopHash
                                )
                        );

                assertEquals(
                        timeout,
                        exception.timeout()
                );

                /*
                 * HeaderSynchronizer owns the request deadline,
                 * not the Peer lifecycle.
                 */
                assertTrue(
                        peer.isReady()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runSilentPeer(
            ServerSocket serverSocket,
            Hash256 locatorHash,
            Hash256 stopHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
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

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            /*
             * Complete a normal handshake.
             */
            BitcoinMessage version =
                    reader.read(input)
                            .orElseThrow();

            assertEquals(
                    "version",
                    version.command()
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            70016,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/silent-header-peer/",
                            0,
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

            /*
             * Receive getheaders, verify it, but deliberately
             * DO NOT send a headers response.
             */
            BitcoinMessage getHeadersWire =
                    reader.read(input)
                            .orElseThrow();

            assertEquals(
                    "getheaders",
                    getHeadersWire.command()
            );

            GetHeadersMessage getHeaders =
                    ru.bitcoin.node.p2p.codec
                            .GetHeadersMessageCodec
                            .decode(
                                    getHeadersWire.payload()
                            );

            assertEquals(
                    List.of(
                            locatorHash
                    ),
                    getHeaders.locatorHashes()
            );

            assertEquals(
                    stopHash,
                    getHeaders.stopHash()
            );

            /*
             * Keep the TCP connection alive.
             *
             * The client must fail because of the explicit
             * HeaderSynchronizer deadline, not EOF/disconnect.
             *
             * The test closes Peer after observing the timeout,
             * which causes EOF here.
             */
            assertEquals(
                    -1,
                    input.read()
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runPeer(
            ServerSocket serverSocket,
            Hash256 locatorHash,
            Hash256 stopHash,
            Block genesis
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
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

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            BitcoinMessage version =
                    reader.read(input)
                            .orElseThrow();

            assertEquals(
                    "version",
                    version.command()
            );

            VersionMessage remoteVersion =
                    new VersionMessage(
                            70016,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/header-sync-test/",
                            0,
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

            BitcoinMessage getHeadersWire =
                    reader.read(input)
                            .orElseThrow();

            assertEquals(
                    "getheaders",
                    getHeadersWire.command()
            );

            GetHeadersMessage getHeaders =
                    ru.bitcoin.node.p2p.codec
                            .GetHeadersMessageCodec
                            .decode(
                                    getHeadersWire.payload()
                            );

            assertEquals(
                    70016,
                    getHeaders.protocolVersion()
            );

            assertEquals(
                    List.of(locatorHash),
                    getHeaders.locatorHashes()
            );

            assertEquals(
                    stopHash,
                    getHeaders.stopHash()
            );

            output.write(
                    encoder.encode(
                            BitcoinMessages.ping(
                                    new PingMessage(
                                            PING_NONCE
                                    )
                            )
                    )
            );

            output.flush();

            BitcoinMessage pong =
                    reader.read(input)
                            .orElseThrow();

            assertEquals(
                    "pong",
                    pong.command()
            );

            assertEquals(
                    PING_NONCE,
                    BitcoinMessages.decodePong(
                            pong
                    ).nonce()
            );

            HeadersMessage response =
                    new HeadersMessage(
                            List.of(
                                    genesis.header()
                            )
                    );

            output.write(
                    encoder.encode(
                            new BitcoinMessage(
                                    "headers",
                                    HeadersMessageCodec.encode(
                                            response
                                    )
                            )
                    )
            );

            output.flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
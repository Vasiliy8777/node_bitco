package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BlockSynchronizerTest {

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    private static final long PING_NONCE =
            0x2122232425262728L;

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldDownloadRequestedBlockAndHandlePing()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runPeer(
                                    serverSocket,
                                    expectedBlock,
                                    expectedHash
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

                BlockSynchronizer synchronizer =
                        new BlockSynchronizer(
                                peer
                        );

                Block downloaded =
                        synchronizer.download(
                                expectedHash
                        );

                assertEquals(
                        expectedHash,
                        downloaded.hash()
                );

                assertEquals(
                        expectedBlock,
                        downloaded
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailImmediatelyWhenPeerReportsRequestedBlockNotFound()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runNotFoundPeer(
                                    serverSocket,
                                    expectedHash
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

                BlockSynchronizer synchronizer =
                        new BlockSynchronizer(
                                peer
                        );

                BlockNotFoundException exception =
                        assertThrows(
                                BlockNotFoundException.class,
                                () ->
                                        synchronizer.download(
                                                expectedHash
                                        )
                        );

                assertEquals(
                        expectedHash,
                        exception.blockHash()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldReportTransportFailureWhenPeerDisconnectsBeforeBlock()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runDisconnectPeer(
                                    serverSocket,
                                    expectedHash
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

                BlockSynchronizer synchronizer =
                        new BlockSynchronizer(
                                peer
                        );

                IOException exception =
                        assertThrows(
                                IOException.class,
                                () ->
                                        synchronizer.download(
                                                expectedHash
                                        )
                        );

                assertFalse(
                        exception instanceof BlockNotFoundException
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runDisconnectPeer(
            ServerSocket serverSocket,
            Hash256 expectedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            /*
             * Deliberately close the socket without sending
             * either block or notfound.
             *
             * This represents a transport-level failure,
             * not a semantic "block unavailable" response.
             */

        } catch (Exception e) {
            throw new RuntimeException(
                    e
            );
        }
    }

    @Test
    void shouldIgnoreUnrelatedNotFoundAndContinueWaitingForRequestedBlock()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        Hash256 unrelatedHash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runUnrelatedNotFoundPeer(
                                    serverSocket,
                                    expectedBlock,
                                    expectedHash,
                                    unrelatedHash
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

                BlockSynchronizer synchronizer =
                        new BlockSynchronizer(
                                peer
                        );

                Block downloaded =
                        synchronizer.download(
                                expectedHash
                        );

                assertEquals(
                        expectedHash,
                        downloaded.hash()
                );

                assertEquals(
                        expectedBlock,
                        downloaded
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldIgnoreNotFoundWithRequestedHashButNonBlockType()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runNonBlockNotFoundPeer(
                                    serverSocket,
                                    expectedBlock,
                                    expectedHash
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

                BlockSynchronizer synchronizer =
                        new BlockSynchronizer(
                                peer
                        );

                Block downloaded =
                        synchronizer.download(
                                expectedHash
                        );

                assertEquals(
                        expectedHash,
                        downloaded.hash()
                );

                assertEquals(
                        expectedBlock,
                        downloaded
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private record PeerSession(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) {
    }

    private static PeerSession performHandshake(
            Socket socket
    ) throws Exception {

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
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        REMOTE_NONCE,
                        "/block-sync-test/",
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

        BitcoinMessage wtxidRelay =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "wtxidrelay",
                wtxidRelay.command()
        );

        BitcoinMessage sendAddrV2 =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "sendaddrv2",
                sendAddrV2.command()
        );

        BitcoinMessage sendCmpct =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "sendcmpct",
                sendCmpct.command()
        );

        BitcoinMessage verack =
                reader.read(input)
                        .orElseThrow();

        assertEquals(
                "verack",
                verack.command()
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

        return new PeerSession(
                reader,
                encoder,
                input,
                output
        );
    }

    private static void assertRequestedBlock(
            PeerSession session,
            Hash256 expectedHash
    ) throws Exception {

        BitcoinMessage getDataWire =
                session.reader()
                        .read(
                                session.input()
                        )
                        .orElseThrow();

        assertEquals(
                "getdata",
                getDataWire.command()
        );

        GetDataMessage getData =
                BitcoinMessages.decodeGetData(
                        getDataWire
                );

        assertEquals(
                1,
                getData.size()
        );

        assertEquals(
                InventoryVector.MSG_WITNESS_BLOCK,
                getData.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                expectedHash,
                getData.inventory()
                        .get(0)
                        .hash()
        );
    }

    private static void runNotFoundPeer(
            ServerSocket serverSocket,
            Hash256 expectedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            NotFoundMessage notFound =
                    new NotFoundMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_WITNESS_BLOCK,
                                            expectedHash
                                    )
                            )
                    );

            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.notFound(
                                                    notFound
                                            )
                                    )
                    );

            session.output()
                    .flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void runUnrelatedNotFoundPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash,
            Hash256 unrelatedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            NotFoundMessage unrelatedNotFound =
                    new NotFoundMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_WITNESS_BLOCK,
                                            unrelatedHash
                                    )
                            )
                    );

            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.notFound(
                                                    unrelatedNotFound
                                            )
                                    )
                    );

            /*
             * Follow it immediately with the block that
             * BlockSynchronizer is actually waiting for.
             */
            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.block(
                                                    new BlockMessage(
                                                            expectedBlock
                                                    )
                                            )
                                    )
                    );

            session.output()
                    .flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void runNonBlockNotFoundPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            /*
             * Same hash, but transaction inventory type.
             *
             * This must NOT satisfy a block download.
             */
            NotFoundMessage unrelatedNotFound =
                    new NotFoundMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_TX,
                                            expectedHash
                                    )
                            )
                    );

            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.notFound(
                                                    unrelatedNotFound
                                            )
                                    )
                    );

            /*
             * The requested block follows.
             * BlockSynchronizer must continue waiting
             * after the unrelated notfound.
             */
            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.block(
                                                    new BlockMessage(
                                                            expectedBlock
                                                    )
                                            )
                                    )
                    );

            session.output()
                    .flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void runPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            /*
             * Unrelated message must still be delegated
             * to Peer.handleMessage().
             */
            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.ping(
                                                    new PingMessage(
                                                            PING_NONCE
                                                    )
                                            )
                                    )
                    );

            session.output()
                    .flush();

            BitcoinMessage pong =
                    session.reader()
                            .read(
                                    session.input()
                            )
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

            session.output()
                    .write(
                            session.encoder()
                                    .encode(
                                            BitcoinMessages.block(
                                                    new BlockMessage(
                                                            expectedBlock
                                                    )
                                            )
                                    )
                    );

            session.output()
                    .flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
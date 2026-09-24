package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.PeerState;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadServiceTest {

    private static final long FIRST_REMOTE_NONCE =
            0x1112131415161718L;

    private static final long SECOND_REMOTE_NONCE =
            0x3132333435363738L;

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    @Test
    void shouldRetryNextReadyPeerWhenFirstPeerReportsBlockNotFound()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        CountDownLatch release =
                new CountDownLatch(
                        1
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CompletableFuture<Void> first =
                    CompletableFuture.runAsync(
                            () -> runNotFoundPeer(
                                    firstServer,
                                    expectedHash,
                                    FIRST_REMOTE_NONCE,
                                    release
                            )
                    );

            CompletableFuture<Void> second =
                    CompletableFuture.runAsync(
                            () -> runBlockPeer(
                                    secondServer,
                                    expectedBlock,
                                    expectedHash,
                                    SECOND_REMOTE_NONCE,
                                    release
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            assertEquals(
                    2,
                    peerManager.readyPeers()
                            .size()
            );

            BlockDownloadService service =
                    new BlockDownloadService(
                            peerManager
                    );

            Block downloaded =
                    service.download(
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

            /*
             * NOTFOUND is a valid protocol response for this
             * particular block. It must not make the peer unusable.
             */
            assertEquals(
                    PeerState.READY,
                    firstPeer.state()
            );

            assertTrue(
                    firstPeer.isReady()
            );

            assertTrue(
                    peerManager.readyPeers()
                            .contains(
                                    firstPeer
                            )
            );

            assertEquals(
                    2,
                    peerManager.readyPeers()
                            .size()
            );

            release.countDown();

            first.get(
                    5,
                    TimeUnit.SECONDS
            );

            second.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static Peer connectPeer(
            int port
    ) throws Exception {

        PeerConnection connection =
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
                );

        try {

            peer.connect(
                    "127.0.0.1",
                    port
            );

            peer.handshake();

            assertTrue(
                    peer.isReady()
            );

            return peer;

        } catch (Exception exception) {

            try {
                peer.close();
            } catch (Exception closeException) {
                exception.addSuppressed(
                        closeException
                );
            }

            throw exception;
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
            Socket socket,
            long remoteNonce
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
                reader.read(
                        input
                ).orElseThrow();

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
                        remoteNonce,
                        "/block-download-service-test/",
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
                reader.read(
                        input
                ).orElseThrow();

        assertEquals(
                "wtxidrelay",
                wtxidRelay.command()
        );

        BitcoinMessage sendAddrV2 =
                reader.read(
                        input
                ).orElseThrow();

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
                reader.read(
                        input
                ).orElseThrow();

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
            Hash256 expectedHash,
            long remoteNonce,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket,
                            remoteNonce
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

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release test peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runBlockPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash,
            long remoteNonce,
            CountDownLatch release
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket,
                            remoteNonce
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
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

            assertTrue(
                    release.await(
                            5,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting to release test peer"
            );

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }

    @Test
    void shouldRetryNextReadyPeerWhenFirstPeerDisconnects()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        CountDownLatch release =
                new CountDownLatch(
                        1
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CompletableFuture<Void> first =
                    CompletableFuture.runAsync(
                            () -> runDisconnectPeer(
                                    firstServer,
                                    expectedHash,
                                    FIRST_REMOTE_NONCE
                            )
                    );

            CompletableFuture<Void> second =
                    CompletableFuture.runAsync(
                            () -> runBlockPeer(
                                    secondServer,
                                    expectedBlock,
                                    expectedHash,
                                    SECOND_REMOTE_NONCE,
                                    release
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            assertEquals(
                    2,
                    peerManager.size()
            );

            BlockDownloadService service =
                    new BlockDownloadService(
                            peerManager
                    );

            Block downloaded =
                    service.download(
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

            /*
             * A transport failure closes the broken peer.
             *
             * PeerManager observes the close event and removes
             * the dead peer completely instead of retaining a
             * CLOSED peer and merely filtering it from readyPeers().
             */
            assertEquals(
                    PeerState.CLOSED,
                    firstPeer.state()
            );

            assertFalse(
                    firstPeer.isReady()
            );

            assertFalse(
                    peerManager.peers()
                            .contains(
                                    firstPeer
                            )
            );

            assertFalse(
                    peerManager.readyPeers()
                            .contains(
                                    firstPeer
                            )
            );

            /*
             * Only the healthy peer remains managed.
             */
            assertEquals(
                    1,
                    peerManager.size()
            );

            assertEquals(
                    1,
                    peerManager.peers()
                            .size()
            );

            assertSame(
                    secondPeer,
                    peerManager.peers()
                            .getFirst()
            );

            assertEquals(
                    1,
                    peerManager.readyPeers()
                            .size()
            );

            assertSame(
                    secondPeer,
                    peerManager.readyPeers()
                            .getFirst()
            );

            release.countDown();

            first.get(
                    5,
                    TimeUnit.SECONDS
            );

            second.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldFailAfterAllReadyPeersFail()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        CountDownLatch release =
                new CountDownLatch(
                        1
                );

        try (ServerSocket firstServer =
                     new ServerSocket(0);

             ServerSocket secondServer =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CompletableFuture<Void> first =
                    CompletableFuture.runAsync(
                            () -> runNotFoundPeer(
                                    firstServer,
                                    expectedHash,
                                    FIRST_REMOTE_NONCE,
                                    release
                            )
                    );

            CompletableFuture<Void> second =
                    CompletableFuture.runAsync(
                            () -> runDisconnectPeer(
                                    secondServer,
                                    expectedHash,
                                    SECOND_REMOTE_NONCE
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            firstServer.getLocalPort()
                    );

            Peer secondPeer =
                    connectPeer(
                            secondServer.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            peerManager.add(
                    secondPeer
            );

            BlockDownloadService service =
                    new BlockDownloadService(
                            peerManager
                    );

            IOException exception =
                    assertThrows(
                            IOException.class,
                            () ->
                                    service.download(
                                            expectedHash
                                    )
                    );

            assertTrue(
                    exception.getMessage()
                            .contains(
                                    expectedHash.toDisplayHex()
                            )
            );

            assertEquals(
                    2,
                    exception.getSuppressed()
                            .length
            );

            /*
             * Both peer-specific failures must remain available
             * for diagnostics instead of being discarded.
             */
            assertInstanceOf(
                    BlockNotFoundException.class,
                    exception.getSuppressed()[0]
            );

            assertInstanceOf(
                    IOException.class,
                    exception.getSuppressed()[1]
            );

            assertFalse(
                    exception.getSuppressed()[1]
                            instanceof BlockNotFoundException
            );

            assertEquals(
                    PeerState.READY,
                    firstPeer.state()
            );

            assertEquals(
                    PeerState.CLOSED,
                    secondPeer.state()
            );

            assertEquals(
                    1,
                    peerManager.readyPeers()
                            .size()
            );

            assertSame(
                    firstPeer,
                    peerManager.readyPeers()
                            .get(0)
            );

            release.countDown();

            first.get(
                    5,
                    TimeUnit.SECONDS
            );

            second.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldDownloadRequestedBlockFromSpecificReadyPeer()
            throws Exception {

        Block expectedBlock =
                GenesisBlockFactory.create(
                        PARAMETERS
                );

        Hash256 expectedHash =
                expectedBlock.hash();

        CountDownLatch release =
                new CountDownLatch(
                        1
                );

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runBlockPeer(
                                    serverSocket,
                                    expectedBlock,
                                    expectedHash,
                                    FIRST_REMOTE_NONCE,
                                    release
                            )
                    );

            Peer peer =
                    connectPeer(
                            serverSocket.getLocalPort()
                    );

            peerManager.add(
                    peer
            );

            BlockDownloadService service =
                    new BlockDownloadService(
                            peerManager
                    );

            Block downloaded =
                    service.download(
                            peer,
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

            assertTrue(
                    peer.isReady()
            );

            release.countDown();

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runDisconnectPeer(
            ServerSocket serverSocket,
            Hash256 expectedHash,
            long remoteNonce
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerSession session =
                    performHandshake(
                            socket,
                            remoteNonce
                    );

            assertRequestedBlock(
                    session,
                    expectedHash
            );

            /*
             * Close the socket without sending block/notfound.
             *
             * BlockSynchronizer must observe EOF and report
             * an IOException to BlockDownloadService.
             */

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
            );
        }
    }
}
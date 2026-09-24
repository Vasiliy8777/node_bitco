package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.BlockMessage;
import ru.bitcoin.node.p2p.message.GetDataMessage;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerBlockDownloadReconnectTest {

    private static final long REMOTE_NONCE =
            0x2122232425262728L;

    @Test
    void shouldResumePendingBlockAfterAllPeersDisappearAndReplacementArrives()
            throws Exception {

        Block block =
                block();

        try (ServerSocket failedServerSocket =
                     new ServerSocket(0);

             ServerSocket replacementServerSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch failedRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch replacementRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseReplacementServer =
                    new CountDownLatch(1);

            /*
             * Peer #1 completes the Bitcoin handshake,
             * receives the block request and then deliberately
             * drops the TCP connection without returning a block.
             */
            CompletableFuture<Void> failedServer =
                    CompletableFuture.runAsync(
                            () -> runDisconnectingPeer(
                                    failedServerSocket,
                                    block.hash(),
                                    failedRequestReceived
                            )
                    );

            /*
             * Peer #2 is available as a TCP listener, but is not
             * connected to PeerManager yet. It represents the
             * replacement peer that a reconnect supervisor would
             * establish later.
             */
            CompletableFuture<Void> replacementServer =
                    CompletableFuture.runAsync(
                            () -> runReplacementPeer(
                                    replacementServerSocket,
                                    block,
                                    replacementRequestReceived,
                                    releaseReplacementServer
                            )
                    );

            Peer firstPeer =
                    connectPeer(
                            failedServerSocket.getLocalPort()
                    );

            peerManager.add(
                    firstPeer
            );

            try (SchedulerBlockDownloadSession session =
                         new SchedulerBlockDownloadSession(
                                 peerManager,
                                 new BlockDownloadService(
                                         peerManager
                                 ),
                                 new BlockDownloadTimeoutPolicy(
                                         Duration.ofMinutes(
                                                 10
                                         )
                                 )
                         )) {

                session.submit(
                        List.of(
                                block.hash()
                        )
                );

                /*
                 * Prove that the original peer really received
                 * the block request before its connection dies.
                 */
                assertTrue(
                        failedRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        ),
                        "Initial peer did not receive the block request"
                );

                /*
                 * awaitCompleted() must survive:
                 *
                 *   first peer failure
                 *       ->
                 *   zero READY peers
                 *       ->
                 *   replacement peer appearing later.
                 */
                CompletableFuture<CompletedBlockDownload>
                        completedFuture =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {

                                        return session.awaitCompleted();

                                    } catch (Exception exception) {

                                        throw new CompletionException(
                                                exception
                                        );
                                    }
                                }
                        );

                /*
                 * PeerMessageReader must observe EOF and PeerManager
                 * must remove the disconnected peer.
                 */
                awaitPeerCount(
                        peerManager,
                        0,
                        Duration.ofSeconds(
                                5
                        )
                );

                assertEquals(
                        1,
                        session.pendingCount(),
                        "Pending block must survive temporary loss of all peers"
                );

                /*
                 * Give awaitCompleted() enough time to execute at
                 * least one zero-peer polling cycle.
                 *
                 * This is important: the test must prove that the
                 * session actually survives the zero-peer state,
                 * rather than adding the replacement immediately.
                 */
                Thread.sleep(
                        500L
                );

                assertTrue(
                        !completedFuture.isDone(),
                        "Block download session must remain pending while no READY peer exists"
                );

                /*
                 * Simulate OutboundPeerSupervisor reconnect:
                 * create a completely new READY Peer and publish
                 * it through the same PeerManager.
                 */
                Peer replacementPeer =
                        connectPeer(
                                replacementServerSocket
                                        .getLocalPort()
                        );

                peerManager.add(
                        replacementPeer
                );

                assertTrue(
                        replacementRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        ),
                        "Replacement peer did not receive the pending block request"
                );

                CompletedBlockDownload completed =
                        completedFuture.get(
                                5,
                                TimeUnit.SECONDS
                        );

                assertEquals(
                        0,
                        completed.index()
                );

                assertEquals(
                        block.hash(),
                        completed.requestedHash()
                );

                assertEquals(
                        block.hash(),
                        completed.block()
                                .hash()
                );

                assertEquals(
                        0,
                        session.pendingCount()
                );

                assertTrue(
                        replacementPeer.isReady(),
                        "Successful replacement peer must remain READY"
                );

            } finally {

                releaseReplacementServer.countDown();
            }

            failedServer.get(
                    5,
                    TimeUnit.SECONDS
            );

            replacementServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runDisconnectingPeer(
            ServerSocket serverSocket,
            Hash256 expectedBlockHash,
            CountDownLatch requestReceived
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerIo io =
                    peerIo(
                            socket
                    );

            completeHandshake(
                    io
            );

            Hash256 requestedHash =
                    readRequestedBlockHash(
                            io
                    );

            assertEquals(
                    expectedBlockHash,
                    requestedHash
            );

            requestReceived.countDown();

            /*
             * Deliberately return from the try-with-resources
             * block without sending BLOCK.
             *
             * Closing the socket must cause the original Peer
             * to leave PeerManager while the requested block
             * remains pending in the session.
             */

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static void runReplacementPeer(
            ServerSocket serverSocket,
            Block block,
            CountDownLatch requestReceived,
            CountDownLatch releaseServer
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            PeerIo io =
                    peerIo(
                            socket
                    );

            completeHandshake(
                    io
            );

            Hash256 requestedHash =
                    readRequestedBlockHash(
                            io
                    );

            assertEquals(
                    block.hash(),
                    requestedHash
            );

            requestReceived.countDown();

            sendBlock(
                    io,
                    block
            );

            /*
             * Keep the connection alive until assertions have
             * verified that the replacement peer remains usable.
             */
            assertTrue(
                    releaseServer.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }

    private static Peer connectPeer(
            int port
    ) throws Exception {

        Peer peer =
                new Peer(
                        new PeerConnection(
                                NetworkParametersRegistry.mainnet(),
                                5_000,
                                5_000
                        ),
                        VersionMessage.DEFAULT_SERVICES,
                        0,
                        true
                );

        peer.connect(
                "127.0.0.1",
                port
        );

        peer.handshake();

        assertTrue(
                peer.isReady()
        );

        assertTrue(
                peer.messageReader()
                        .isStarted()
        );

        return peer;
    }

    private static void completeHandshake(
            PeerIo io
    ) throws Exception {

        BitcoinMessage clientVersion =
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow();

        assertEquals(
                "version",
                clientVersion.command()
        );

        VersionMessage version =
                new VersionMessage(
                        VersionMessage.CURRENT_PROTOCOL_VERSION,
                        VersionMessage.DEFAULT_SERVICES,
                        1_700_000_000L,
                        NetworkAddress.unspecified(),
                        NetworkAddress.unspecified(),
                        REMOTE_NONCE,
                        "/scheduler-reconnect-test/",
                        0,
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

        assertEquals(
                "wtxidrelay",
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "sendaddrv2",
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "sendcmpct",
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow()
                        .command()
        );

        assertEquals(
                "verack",
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow()
                        .command()
        );

        io.output().write(
                io.encoder().encode(
                        BitcoinMessages.verack()
                )
        );

        io.output().flush();
    }

    private static Hash256 readRequestedBlockHash(
            PeerIo io
    ) throws Exception {

        BitcoinMessage message =
                io.reader()
                        .read(
                                io.input()
                        )
                        .orElseThrow();

        assertEquals(
                "getdata",
                message.command()
        );

        GetDataMessage getData =
                BitcoinMessages.decodeGetData(
                        message
                );

        assertEquals(
                1,
                getData.size()
        );

        InventoryVector vector =
                getData.inventory()
                        .get(0);

        assertEquals(
                InventoryVector.MSG_WITNESS_BLOCK,
                vector.type()
        );

        return vector.hash();
    }

    private static void sendBlock(
            PeerIo io,
            Block block
    ) throws Exception {

        io.output().write(
                io.encoder().encode(
                        BitcoinMessages.block(
                                new BlockMessage(
                                        block
                                )
                        )
                )
        );

        io.output().flush();
    }

    private static PeerIo peerIo(
            Socket socket
    ) throws Exception {

        socket.setSoTimeout(
                5_000
        );

        return new PeerIo(
                new BitcoinMessageStreamReader(
                        new BitcoinMessageDecoder(
                                NetworkParametersRegistry.mainnet()
                        )
                ),
                new BitcoinMessageEncoder(
                        NetworkParametersRegistry.mainnet()
                ),
                new BufferedInputStream(
                        socket.getInputStream()
                ),
                new BufferedOutputStream(
                        socket.getOutputStream()
                )
        );
    }

    private static Block block() {

        Block genesis =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        BlockHeader header =
                genesis.header();

        return new Block(
                new BlockHeader(
                        header.version(),
                        header.previousBlockHash(),
                        header.merkleRoot(),
                        header.timestamp(),
                        header.bits(),
                        new UInt32(
                                1L
                        )
                ),
                genesis.transactions()
        );
    }

    private static void awaitPeerCount(
            PeerManager peerManager,
            int expectedCount,
            Duration timeout
    ) throws Exception {

        long deadline =
                System.nanoTime()
                        + timeout.toNanos();

        while (System.nanoTime()
                < deadline) {

            if (peerManager.size()
                    == expectedCount) {

                return;
            }

            Thread.sleep(
                    10L
            );
        }

        assertEquals(
                expectedCount,
                peerManager.size(),
                "PeerManager did not reach expected peer count"
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
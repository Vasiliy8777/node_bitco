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
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadSchedulerTest {

    private static final long REMOTE_NONCE =
            0x1112131415161718L;

    @Test
    void shouldAllowAtMostSixteenBlocksInFlightPerPeer()
            throws Exception {

        List<Block> blocks =
                blocks(
                        BlockDownloadScheduler
                                .MAX_BLOCKS_IN_FLIGHT_PER_PEER + 1
                );

        Map<Hash256, Block> blocksByHash =
                new HashMap<>();

        for (Block block : blocks) {
            blocksByHash.put(
                    block.hash(),
                    block
            );
        }

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch releaseServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runWindowPeer(
                                    serverSocket,
                                    blocksByHash,
                                    releaseServer
                            )
                    );

            Peer peer =
                    connectPeer(
                            serverSocket.getLocalPort()
                    );

            peerManager.add(
                    peer
            );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            new BlockDownloadService(
                                    peerManager
                            ),
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMinutes(10))
                    );

            List<Hash256> requestedHashes =
                    blocks.stream()
                            .map(Block::hash)
                            .toList();

            List<Block> downloaded;

            try {
                downloaded =
                        scheduler.download(
                                requestedHashes
                        );

                assertEquals(
                        blocks.size(),
                        downloaded.size()
                );

                assertEquals(
                        requestedHashes,
                        downloaded.stream()
                                .map(Block::hash)
                                .toList()
                );

                /*
                 * The remote socket is deliberately kept open
                 * until this assertion has completed.
                 *
                 * A successful block download must not make
                 * a healthy peer unusable.
                 */
                assertTrue(
                        peer.isReady()
                );

            } finally {
                /*
                 * Only now may the fake remote peer terminate.
                 * Closing its socket causes the background reader
                 * to observe EOF and transition Peer to CLOSED.
                 */
                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldRetryBlockOnAnotherPeerAfterDownloadTimeout()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket stalledServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch stalledRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseStalledServer =
                    new CountDownLatch(1);

            CountDownLatch releaseHealthyServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> stalledServer =
                    CompletableFuture.runAsync(
                            () -> runStalledPeer(
                                    stalledServerSocket,
                                    block.hash(),
                                    stalledRequestReceived,
                                    releaseStalledServer
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runHealthyRetryPeer(
                                    healthyServerSocket,
                                    block,
                                    releaseHealthyServer
                            )
                    );

            Peer stalledPeer =
                    connectPeer(
                            stalledServerSocket.getLocalPort()
                    );

            Peer healthyPeer =
                    connectPeer(
                            healthyServerSocket.getLocalPort()
                    );

            /*
             * Ordering is intentional.
             *
             * With one requested block the scheduler's round-robin
             * assignment must initially choose stalledPeer.
             */
            peerManager.add(
                    stalledPeer
            );

            peerManager.add(
                    healthyPeer
            );

            BlockDownloadScheduler scheduler =
                    new BlockDownloadScheduler(
                            peerManager,
                            new BlockDownloadService(
                                    peerManager
                            ),
                            new BlockDownloadTimeoutPolicy(
                                    Duration.ofMillis(
                                            50
                                    )
                            )
                    );

            List<Block> downloaded;

            try {

                downloaded =
                        scheduler.download(
                                List.of(
                                        block.hash()
                                )
                        );

                assertTrue(
                        stalledRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        )
                );

                assertEquals(
                        1,
                        downloaded.size()
                );

                assertEquals(
                        block.hash(),
                        downloaded.get(0)
                                .hash()
                );

                assertFalse(
                        stalledPeer.isReady()
                );

                /*
                 * Timeout of another peer must not damage
                 * the peer that successfully supplied the retry.
                 */
                assertTrue(
                        healthyPeer.isReady()
                );

            } finally {

                releaseStalledServer.countDown();
                releaseHealthyServer.countDown();
            }

            stalledServer.get(
                    5,
                    TimeUnit.SECONDS
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void sessionShouldGrowWorkersWhenReadyPeerSetExpands()
            throws Exception {

        List<Block> blocks =
                blocks(
                        BlockDownloadScheduler.MAX_BLOCKS_IN_FLIGHT_PER_PEER + 1
                );

        try (ServerSocket firstServerSocket = new ServerSocket(0);
             ServerSocket secondServerSocket = new ServerSocket(0);
             PeerManager peerManager = new PeerManager()) {

            CountDownLatch firstWindowReceived = new CountDownLatch(1);
            CountDownLatch secondPeerReceivedRequest = new CountDownLatch(1);
            CountDownLatch releaseServers = new CountDownLatch(1);

            CompletableFuture<Void> firstServer = CompletableFuture.runAsync(() -> {
                try (Socket socket = firstServerSocket.accept()) {
                    PeerIo io = peerIo(socket);
                    completeHandshake(io);
                    for (int i = 0;
                         i < BlockDownloadScheduler.MAX_BLOCKS_IN_FLIGHT_PER_PEER;
                         i++) {
                        readRequestedBlockHash(io);
                    }
                    firstWindowReceived.countDown();
                    assertTrue(releaseServers.await(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            Hash256 seventeenthHash =
                    blocks.get(BlockDownloadScheduler.MAX_BLOCKS_IN_FLIGHT_PER_PEER).hash();

            CompletableFuture<Void> secondServer = CompletableFuture.runAsync(() -> {
                try (Socket socket = secondServerSocket.accept()) {
                    PeerIo io = peerIo(socket);
                    completeHandshake(io);
                    assertEquals(seventeenthHash, readRequestedBlockHash(io));
                    secondPeerReceivedRequest.countDown();
                    assertTrue(releaseServers.await(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            Peer firstPeer = connectPeer(firstServerSocket.getLocalPort());
            peerManager.add(firstPeer);

            try (SchedulerBlockDownloadSession session =
                         new SchedulerBlockDownloadSession(
                                 peerManager,
                                 new BlockDownloadService(peerManager),
                                 new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10))
                         )) {

                session.submit(blocks.stream().map(Block::hash).toList());

                assertTrue(
                        firstWindowReceived.await(5, TimeUnit.SECONDS),
                        "Initial peer did not fill its sixteen in-flight slots"
                );

                Peer secondPeer = connectPeer(secondServerSocket.getLocalPort());
                peerManager.add(secondPeer);

                /*
                 * pollCompleted() refreshes the READY peer snapshot. The new peer
                 * must get an actual worker immediately even though all workers
                 * created for the initial peer are blocked in network reads.
                 */
                assertTrue(session.pollCompleted(Duration.ofMillis(50)).isEmpty());

                assertTrue(
                        secondPeerReceivedRequest.await(2, TimeUnit.SECONDS),
                        "New READY peer was starved behind workers blocked on the initial peer"
                );
            } finally {
                releaseServers.countDown();
            }

            firstServer.get(5, TimeUnit.SECONDS);
            secondServer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldUseFiniteCompletionCheckInterval() {

        assertTrue(
                BlockDownloadScheduler.COMPLETION_CHECK_INTERVAL
                        .compareTo(
                                Duration.ZERO
                        ) > 0
        );

        assertTrue(
                BlockDownloadScheduler.COMPLETION_CHECK_INTERVAL
                        .compareTo(
                                Duration.ofSeconds(1)
                        ) < 0
        );
    }

    private static void runWindowPeer(
            ServerSocket serverSocket,
            Map<Hash256, Block> blocksByHash,
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

            List<Hash256> firstWindow =
                    new ArrayList<>();

            for (int i = 0;
                 i < BlockDownloadScheduler
                         .MAX_BLOCKS_IN_FLIGHT_PER_PEER;
                 i++) {

                firstWindow.add(
                        readRequestedBlockHash(
                                io
                        )
                );
            }

            /*
             * The scheduler must not send request 17 while
             * all sixteen slots for this peer are occupied.
             */
            socket.setSoTimeout(
                    250
            );

            assertThrows(
                    SocketTimeoutException.class,
                    () -> io.reader()
                            .read(
                                    io.input()
                            )
            );

            socket.setSoTimeout(
                    5_000
            );

            Hash256 completedHash =
                    firstWindow.get(0);

            sendBlock(
                    io,
                    requireBlock(
                            blocksByHash,
                            completedHash
                    )
            );

            /*
             * Completing one request frees exactly one slot,
             * so request 17 may now be issued.
             */
            Hash256 seventeenthHash =
                    readRequestedBlockHash(
                            io
                    );

            assertFalse(
                    firstWindow.contains(
                            seventeenthHash
                    )
            );

            for (int i = 1;
                 i < firstWindow.size();
                 i++) {

                sendBlock(
                        io,
                        requireBlock(
                                blocksByHash,
                                firstWindow.get(i)
                        )
                );
            }

            sendBlock(
                    io,
                    requireBlock(
                            blocksByHash,
                            seventeenthHash
                    )
            );

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

    private static Block requireBlock(
            Map<Hash256, Block> blocksByHash,
            Hash256 hash
    ) {
        Block block =
                blocksByHash.get(
                        hash
                );

        assertNotNull(
                block
        );

        return block;
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

    private static List<Block> blocks(
            int count
    ) {
        Block genesis =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        List<Block> blocks =
                new ArrayList<>(
                        count
                );

        for (int i = 0;
             i < count;
             i++) {

            BlockHeader header =
                    genesis.header();

            blocks.add(
                    new Block(
                            new BlockHeader(
                                    header.version(),
                                    header.previousBlockHash(),
                                    header.merkleRoot(),
                                    header.timestamp(),
                                    header.bits(),
                                    new UInt32(
                                            i + 1L
                                    )
                            ),
                            genesis.transactions()
                    )
            );
        }

        return List.copyOf(
                blocks
        );
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
                        "/scheduler-test/",
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
                "sendcmpct",
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
                        BitcoinMessages.verack()
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

    private static void runHealthyRetryPeer(
            ServerSocket serverSocket,
            Block block,
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

            sendBlock(
                    io,
                    block
            );

            /*
             * Keep the healthy connection alive until the test
             * verifies that the scheduler did not close this peer.
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

    @Test
    void sessionShouldReturnBlocksInCompletionOrder()
            throws Exception {

        List<Block> blocks =
                blocks(
                        2
                );

        Block first =
                blocks.get(0);

        Block second =
                blocks.get(1);

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            /*
             * Server sends B2 first and then waits here.
             *
             * B1 cannot possibly complete until the test has
             * observed B2 through awaitCompleted().
             */
            CountDownLatch secondCompletionObserved =
                    new CountDownLatch(1);

            CountDownLatch releaseServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {

                                try (Socket socket =
                                             serverSocket.accept()) {

                                    PeerIo io =
                                            peerIo(
                                                    socket
                                            );

                                    completeHandshake(
                                            io
                                    );

                                    /*
                                     * Both downloads are submitted concurrently.
                                     *
                                     * PeerConnection serializes complete wire messages, but the
                                     * executor is free to schedule either download task first.
                                     * Therefore wire request order is intentionally irrelevant.
                                     */
                                    Hash256 firstRequest =
                                            readRequestedBlockHash(
                                                    io
                                            );

                                    Hash256 secondRequest =
                                            readRequestedBlockHash(
                                                    io
                                            );

                                    assertNotEquals(
                                            firstRequest,
                                            secondRequest
                                    );

                                    assertEquals(
                                            Set.of(
                                                    first.hash(),
                                                    second.hash()
                                            ),
                                            Set.of(
                                                    firstRequest,
                                                    secondRequest
                                            )
                                    );

                                    /*
                                     * Complete B2 first.
                                     */
                                    sendBlock(
                                            io,
                                            second
                                    );

                                    /*
                                     * Do not send B1 until the caller has
                                     * actually observed B2 as the first
                                     * completed download.
                                     */
                                    assertTrue(
                                            secondCompletionObserved.await(
                                                    5,
                                                    TimeUnit.SECONDS
                                            )
                                    );

                                    sendBlock(
                                            io,
                                            first
                                    );

                                    /*
                                     * Keep the healthy peer connected until the test has
                                     * observed both completed downloads.
                                     *
                                     * There is intentionally no timeout here: the test itself
                                     * is bounded by server.get(5, TimeUnit.SECONDS) after the
                                     * latch is released in finally.
                                     */
                                    releaseServer.await();

                                } catch (Exception exception) {
                                    throw new RuntimeException(
                                            exception
                                    );
                                }
                            }
                    );

            Peer peer =
                    connectPeer(
                            serverSocket.getLocalPort()
                    );

            peerManager.add(
                    peer
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
                                first.hash(),
                                second.hash()
                        )
                );

                assertEquals(
                        2,
                        session.pendingCount()
                );

                assertSame(
                        peer,
                        session.inFlightPeer(
                                        first.hash()
                                )
                                .orElseThrow()
                );

                assertSame(
                        peer,
                        session.inFlightPeer(
                                        second.hash()
                                )
                                .orElseThrow()
                );

                CompletedBlockDownload completedSecond =
                        session.awaitCompleted();

                /*
                 * B1 has not even been sent by the remote peer yet,
                 * therefore this completion must be B2.
                 */
                assertEquals(
                        1,
                        completedSecond.index()
                );

                assertEquals(
                        second.hash(),
                        completedSecond.requestedHash()
                );

                assertEquals(
                        second.hash(),
                        completedSecond.block()
                                .hash()
                );

                assertEquals(
                        1,
                        session.pendingCount()
                );

                /*
                 * B2 completed first, therefore its in-flight ownership
                 * must already be gone.
                 *
                 * B1 is still deliberately withheld by the remote peer,
                 * so B1 must remain owned by this peer.
                 */
                assertTrue(
                        session.inFlightPeer(
                                        second.hash()
                                )
                                .isEmpty()
                );

                assertSame(
                        peer,
                        session.inFlightPeer(
                                        first.hash()
                                )
                                .orElseThrow()
                );

                /*
                 * Only now allow the remote peer to send B1.
                 */
                secondCompletionObserved.countDown();

                CompletedBlockDownload completedFirst =
                        session.awaitCompleted();

                assertEquals(
                        0,
                        completedFirst.index()
                );

                assertEquals(
                        first.hash(),
                        completedFirst.requestedHash()
                );

                assertEquals(
                        first.hash(),
                        completedFirst.block()
                                .hash()
                );

                assertEquals(
                        0,
                        session.pendingCount()
                );

                assertTrue(
                        session.inFlightPeer(
                                        first.hash()
                                )
                                .isEmpty()
                );

                assertTrue(
                        session.inFlightPeer(
                                        second.hash()
                                )
                                .isEmpty()
                );

                assertTrue(
                        peer.isReady()
                );

            } finally {

                /*
                 * Always release both waits, including assertion
                 * failure paths, so the server task cannot hang.
                 */
                secondCompletionObserved.countDown();
                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void sessionShouldReleasePeerDownloadsForExternalFailure()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket failedServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch failedRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseFailedServer =
                    new CountDownLatch(1);

            CountDownLatch releaseHealthyServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> failedServer =
                    CompletableFuture.runAsync(
                            () -> runStalledPeer(
                                    failedServerSocket,
                                    block.hash(),
                                    failedRequestReceived,
                                    releaseFailedServer
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runHealthyRetryPeer(
                                    healthyServerSocket,
                                    block,
                                    releaseHealthyServer
                            )
                    );

            Peer failedPeer =
                    connectPeer(
                            failedServerSocket.getLocalPort()
                    );

            Peer healthyPeer =
                    connectPeer(
                            healthyServerSocket.getLocalPort()
                    );

            /*
             * The first assignment must go to failedPeer.
             */
            peerManager.add(
                    failedPeer
            );

            peerManager.add(
                    healthyPeer
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

                assertTrue(
                        failedRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        )
                );

                assertEquals(
                        1,
                        session.pendingCount()
                );

                assertSame(
                        failedPeer,
                        session.inFlightPeer(
                                        block.hash()
                                )
                                .orElseThrow()
                );

                IOException stallFailure =
                        new IOException(
                                "Peer stalled block download window"
                        );

                /*
                 * This is the NEW contract.
                 *
                 * It must release all downloads owned by failedPeer
                 * without marking the requested blocks completed.
                 */
                session.failPeer(
                        failedPeer,
                        stallFailure
                );

                assertEquals(
                        1,
                        session.pendingCount()
                );

                assertTrue(
                        session.inFlightPeer(
                                        block.hash()
                                )
                                .isEmpty()
                );

                /*
                 * Closing the peer is intentionally separate from
                 * releasing session ownership.
                 *
                 * BlockSyncCoordinator will own the stall-disconnect
                 * decision.
                 */
                failedPeer.close();

                /*
                 * awaitCompleted() must assign the now-free block to
                 * the remaining ready peer.
                 */
                CompletedBlockDownload completed =
                        session.awaitCompleted();

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

                assertFalse(
                        failedPeer.isReady()
                );

                assertTrue(
                        healthyPeer.isReady()
                );

            } finally {

                releaseFailedServer.countDown();
                releaseHealthyServer.countDown();
            }

            failedServer.get(
                    5,
                    TimeUnit.SECONDS
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void sessionPollCompletedShouldReturnEmptyWhileBlockIsStillInFlight()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch requestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runStalledPeer(
                                    serverSocket,
                                    block.hash(),
                                    requestReceived,
                                    releaseServer
                            )
                    );

            try {
                Peer peer =
                        connectPeer(
                                serverSocket.getLocalPort()
                        );

                peerManager.add(
                        peer
                );

                try (SchedulerBlockDownloadSession session =
                             new SchedulerBlockDownloadSession(
                                     peerManager,
                                     new BlockDownloadService(
                                             peerManager
                                     ),
                                     new BlockDownloadTimeoutPolicy(
                                             Duration.ofMinutes(10)
                                     )
                             )) {

                    session.submit(
                            List.of(
                                    block.hash()
                            )
                    );

                    assertTrue(
                            requestReceived.await(
                                    5,
                                    TimeUnit.SECONDS
                            )
                    );

                    Optional<CompletedBlockDownload> completed =
                            session.pollCompleted(
                                    Duration.ofMillis(50)
                            );

                    assertTrue(
                            completed.isEmpty()
                    );

                    assertEquals(
                            1,
                            session.pendingCount()
                    );

                    assertEquals(
                            Optional.of(peer),
                            session.inFlightPeer(
                                    block.hash()
                            )
                    );

                    assertTrue(
                            peer.isReady()
                    );
                }

            } finally {

                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void sessionPollCompletedShouldReturnWithoutCancellingHealthyInFlightDownload()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket serverSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch requestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runStalledPeer(
                                    serverSocket,
                                    block.hash(),
                                    requestReceived,
                                    releaseServer
                            )
                    );

            try {

                Peer peer =
                        connectPeer(
                                serverSocket.getLocalPort()
                        );

                peerManager.add(
                        peer
                );

                try (SchedulerBlockDownloadSession session =
                             new SchedulerBlockDownloadSession(
                                     peerManager,
                                     new BlockDownloadService(
                                             peerManager
                                     ),
                                     new BlockDownloadTimeoutPolicy(
                                             Duration.ofMinutes(10)
                                     )
                             )) {

                    session.submit(
                            List.of(
                                    block.hash()
                            )
                    );

                    assertTrue(
                            requestReceived.await(
                                    5,
                                    TimeUnit.SECONDS
                            )
                    );

                    Optional<CompletedBlockDownload> completed =
                            session.pollCompleted(
                                    Duration.ofMillis(50)
                            );

                    assertTrue(
                            completed.isEmpty()
                    );

                    assertEquals(
                            1,
                            session.pendingCount()
                    );

                    assertEquals(
                            Optional.of(peer),
                            session.inFlightPeer(
                                    block.hash()
                            )
                    );

                    assertTrue(
                            peer.isReady()
                    );
                }

            } finally {

                releaseServer.countDown();
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runStalledPeer(
            ServerSocket serverSocket,
            Hash256 expectedBlockHash,
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
                    expectedBlockHash,
                    requestedHash
            );

            requestReceived.countDown();

            /*
             * Deliberately do not send the block.
             *
             * The scheduler must eventually detect the download
             * timeout and close this peer.
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

    @Test
    void sessionShouldRetryFailedBlockOnAnotherPeer()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket failedServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch failedRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseFailedServer =
                    new CountDownLatch(1);

            CountDownLatch releaseHealthyServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> failedServer =
                    CompletableFuture.runAsync(
                            () -> runNotFoundPeer(
                                    failedServerSocket,
                                    block.hash(),
                                    failedRequestReceived,
                                    releaseFailedServer
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runHealthyRetryPeer(
                                    healthyServerSocket,
                                    block,
                                    releaseHealthyServer
                            )
                    );

            Peer failedPeer =
                    connectPeer(
                            failedServerSocket.getLocalPort()
                    );

            Peer healthyPeer =
                    connectPeer(
                            healthyServerSocket.getLocalPort()
                    );

            /*
             * Ordering is intentional.
             *
             * With one block the first assignment must go
             * to failedPeer.
             */
            peerManager.add(
                    failedPeer
            );

            peerManager.add(
                    healthyPeer
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

                assertTrue(
                        failedRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        )
                );

                CompletedBlockDownload completed =
                        session.awaitCompleted();

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

                /*
                 * NOTFOUND means that this peer does not have
                 * this block. It is not a transport failure,
                 * therefore BlockDownloadService must leave
                 * the peer usable.
                 */
                assertTrue(
                        failedPeer.isReady()
                );

                assertTrue(
                        healthyPeer.isReady()
                );

            } finally {

                releaseFailedServer.countDown();
                releaseHealthyServer.countDown();
            }

            failedServer.get(
                    5,
                    TimeUnit.SECONDS
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runNotFoundPeer(
            ServerSocket serverSocket,
            Hash256 expectedBlockHash,
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
                    expectedBlockHash,
                    requestedHash
            );

            requestReceived.countDown();

            NotFoundMessage notFound =
                    new NotFoundMessage(
                            List.of(
                                    new InventoryVector(
                                            InventoryVector.MSG_WITNESS_BLOCK,
                                            requestedHash
                                    )
                            )
                    );

            io.output().write(
                    io.encoder().encode(
                            BitcoinMessages.notFound(
                                    notFound
                            )
                    )
            );

            io.output().flush();

            /*
             * Keep the connection alive so the test can verify
             * that NOTFOUND did not close an otherwise healthy peer.
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

    @Test
    void sessionShouldRetryBlockOnAnotherPeerAfterDownloadTimeout()
            throws Exception {

        Block block =
                blocks(1)
                        .get(0);

        try (ServerSocket stalledServerSocket =
                     new ServerSocket(0);

             ServerSocket healthyServerSocket =
                     new ServerSocket(0);

             PeerManager peerManager =
                     new PeerManager()) {

            CountDownLatch stalledRequestReceived =
                    new CountDownLatch(1);

            CountDownLatch releaseStalledServer =
                    new CountDownLatch(1);

            CountDownLatch releaseHealthyServer =
                    new CountDownLatch(1);

            CompletableFuture<Void> stalledServer =
                    CompletableFuture.runAsync(
                            () -> runStalledPeer(
                                    stalledServerSocket,
                                    block.hash(),
                                    stalledRequestReceived,
                                    releaseStalledServer
                            )
                    );

            CompletableFuture<Void> healthyServer =
                    CompletableFuture.runAsync(
                            () -> runHealthyRetryPeer(
                                    healthyServerSocket,
                                    block,
                                    releaseHealthyServer
                            )
                    );

            Peer stalledPeer =
                    connectPeer(
                            stalledServerSocket.getLocalPort()
                    );

            Peer healthyPeer =
                    connectPeer(
                            healthyServerSocket.getLocalPort()
                    );

            peerManager.add(
                    stalledPeer
            );

            peerManager.add(
                    healthyPeer
            );

            try (SchedulerBlockDownloadSession session =
                         new SchedulerBlockDownloadSession(
                                 peerManager,
                                 new BlockDownloadService(
                                         peerManager
                                 ),
                                 new BlockDownloadTimeoutPolicy(
                                         Duration.ofMillis(
                                                 50
                                         )
                                 )
                         )) {

                session.submit(
                        List.of(
                                block.hash()
                        )
                );

                assertTrue(
                        stalledRequestReceived.await(
                                5,
                                TimeUnit.SECONDS
                        )
                );

                CompletedBlockDownload completed =
                        session.awaitCompleted();

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

                /*
                 * Timeout is peer-wide and closes the stalled peer.
                 */
                assertFalse(
                        stalledPeer.isReady()
                );

                /*
                 * The retry peer must remain healthy.
                 */
                assertTrue(
                        healthyPeer.isReady()
                );

            } finally {

                releaseStalledServer.countDown();
                releaseHealthyServer.countDown();
            }

            stalledServer.get(
                    5,
                    TimeUnit.SECONDS
            );

            healthyServer.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void completedRequestsKeepTheirIdentityAcrossStreamingBatches() throws Exception {
        var blocks = blocks(96);
        try (var peers = new PeerManager();
             var connection = new PeerConnection(NetworkParametersRegistry.mainnet(), 5_000, 5_000);
             var source = new Peer(connection, VersionMessage.DEFAULT_SERVICES, 0, true)) {
            var scheduler = new BlockDownloadScheduler(peers, new BlockDownloadService(peers),
                    new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10)));
            try (var session = scheduler.openSession()) {
                for (int offset = 0; offset < blocks.size(); offset += 16) {
                    var batch = blocks.subList(offset, offset + 16);
                    session.submit(batch.stream().map(Block::hash).toList());
                    // Complete out of order and consume later: queued completions still count as pending.
                    for (var block : batch.reversed()) {
                        assertTrue(scheduler.acceptBlock(source, block));
                        assertFalse(scheduler.hasPendingBlock(block.hash()));
                        assertTrue(scheduler.hasSubmittedBlock(block.hash()));
                        assertFalse(scheduler.acceptBlock(source, block));
                    }
                    assertEquals(16, session.pendingCount());
                    for (int index = offset + 15; index >= offset; index--) {
                        var completed = session.awaitCompleted();
                        assertEquals(blocks.get(index).hash(), completed.requestedHash());
                        assertEquals(index, completed.index());
                    }
                    assertEquals(0, session.pendingCount());
                    assertThrows(IllegalArgumentException.class, () -> session.submit(List.of(blocks.getFirst().hash())));
                }
                assertTrue(scheduler.hasSubmittedBlock(blocks.getFirst().hash()));
            }
            assertFalse(scheduler.hasSubmittedBlock(blocks.getFirst().hash()));
        }
    }
    @Test
    void deeperPipelineHonorsPerPeerAndSharedBudgetsAndRefillsOneSlot() throws Exception {
        for (int peerCount : new int[]{1, 3}) {
            var requested = new java.util.concurrent.LinkedBlockingQueue<BudgetRequest>();
            var done = new java.util.concurrent.atomic.AtomicBoolean();
            var sockets = new ArrayList<ServerSocket>();
            var servers = new ArrayList<CompletableFuture<Void>>();
            var streams = new ArrayList<CompletableFuture<PeerIo>>();
            var blocks = blocks(300);
            var byHash = new HashMap<Hash256, Block>();
            blocks.forEach(block -> byHash.put(block.hash(), block));
            try (var peers = new PeerManager()) {
                try {
                    for (int i = 0; i < peerCount; i++) {
                        int owner = i;
                        var server = new ServerSocket(0);
                        sockets.add(server);
                        var stream = new CompletableFuture<PeerIo>();
                        streams.add(stream);
                        servers.add(CompletableFuture.runAsync(() -> {
                            try (var socket = server.accept()) {
                                var io = peerIo(socket);
                                completeHandshake(io);
                                stream.complete(io);
                                socket.setSoTimeout(100);
                                while (!done.get()) {
                                    try { requested.add(new BudgetRequest(owner, readRequestedBlockHash(io))); }
                                    catch (SocketTimeoutException ignored) { }
                                }
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        }));
                        peers.add(connectPeer(server.getLocalPort()));
                    }
                    var scheduler = new BlockDownloadScheduler(peers, new BlockDownloadService(peers),
                            new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10)), 128);
                    try (var session = scheduler.openSession()) {
                        session.submit(blocks.stream().map(Block::hash).toList());
                        int budget = Math.min(128 * peerCount, BlockDownloadScheduler.MAX_TOTAL_BLOCKS_IN_FLIGHT);
                        int[] perPeer = new int[peerCount];
                        var hashes = new HashSet<Hash256>();
                        BudgetRequest first = null;
                        for (int i = 0; i < budget; i++) {
                            var request = requested.poll(5, TimeUnit.SECONDS);
                            assertNotNull(request);
                            if (request.hash().equals(blocks.getFirst().hash())) first = request;
                            assertTrue(hashes.add(request.hash()));
                            assertTrue(++perPeer[request.peer()] <= 128);
                        }
                        assertNull(requested.poll(200, TimeUnit.MILLISECONDS), "Request budget exceeded");
                        for (int count : perPeer) assertTrue(count > 0, "Ready peer was starved");
                        if (peerCount == 3) {
                            // With 256 assignments, round-robin next selects peer 1. That peer
                            // owns this failed request: its free slot must not go to a later block.
                            var blockedHash = blocks.get(1).hash();
                            var originalOwner = peers.readyPeers().get(1);
                            assertSame(originalOwner, session.inFlightPeer(blockedHash).orElseThrow());
                            assertTrue(session.retryBlock(blockedHash, originalOwner, new IOException("frontier stalled")));
                            var rescue = requested.poll(5, TimeUnit.SECONDS);
                            assertNotNull(rescue);
                            assertEquals(blockedHash, rescue.hash(), "Later work consumed the frontier rescue slot");
                            assertNotEquals(1, rescue.peer());
                            assertNull(requested.poll(200, TimeUnit.MILLISECONDS));
                        }
                        sendBlock(streams.get(first.peer()).get(5, TimeUnit.SECONDS), byHash.get(first.hash()));
                        assertEquals(first.hash(), session.awaitCompleted().requestedHash());
                        var refill = requested.poll(5, TimeUnit.SECONDS);
                        assertNotNull(refill, "Completion did not refill the pipeline");
                        assertTrue(hashes.add(refill.hash()));
                        assertNull(requested.poll(200, TimeUnit.MILLISECONDS), "One completion freed more than one slot");
                    }
                } finally {
                    done.set(true);
                    for (var server : sockets) server.close();
                    for (var server : servers) server.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void rejectsUnboundedPerPeerPipelineSettings() throws Exception {
        try (var peers = new PeerManager()) {
            var downloads = new BlockDownloadService(peers);
            var timeout = new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10));
            for (int invalid : new int[]{-1, 0, 257, Integer.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class,
                        () -> new BlockDownloadScheduler(peers, downloads, timeout, invalid));
            }
            assertEquals(32, new BlockDownloadScheduler(peers, downloads, timeout).maxBlocksInFlightPerPeer());
        }
    }
    private record BudgetRequest(int peer, Hash256 hash) { }
    private record PeerIo(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) {
    }

    @Test
    void compactBlockCanCompletePendingDownloadBeforePeerAssignment()
            throws Exception {

        Block block = blocks(1).getFirst();

        try (PeerManager peerManager = new PeerManager();
             PeerConnection sourceConnection = new PeerConnection(
                     NetworkParametersRegistry.mainnet(),
                     5_000,
                     5_000
             );
             Peer sourcePeer = new Peer(
                     sourceConnection,
                     VersionMessage.DEFAULT_SERVICES,
                     0,
                     true
             )) {

            BlockDownloadScheduler scheduler = new BlockDownloadScheduler(
                    peerManager,
                    new BlockDownloadService(peerManager),
                    new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10))
            );

            try (BlockDownloadSession session = scheduler.openSession()) {
                session.submit(List.of(block.hash()));

                assertEquals(1, session.pendingCount());
                assertTrue(scheduler.hasPendingBlock(block.hash()));
                assertTrue(scheduler.hasSubmittedBlock(block.hash()));
                assertTrue(scheduler.acceptBlock(sourcePeer, block));

                // Logical completion is one-shot, but ownership remains with the
                // active session until it closes. This suppresses a late duplicate
                // alternative delivery from entering a second validation path.
                assertFalse(scheduler.hasPendingBlock(block.hash()));
                assertTrue(scheduler.hasSubmittedBlock(block.hash()));
                assertFalse(scheduler.acceptBlock(sourcePeer, block));

                CompletedBlockDownload completed = session.awaitCompleted();

                assertEquals(block.hash(), completed.requestedHash());
                assertEquals(block, completed.block());
                assertSame(sourcePeer, completed.sourcePeer());
                assertEquals(0, session.pendingCount());
                assertTrue(scheduler.hasSubmittedBlock(block.hash()));
                assertFalse(scheduler.acceptBlock(sourcePeer, block));
            }

            assertFalse(scheduler.hasSubmittedBlock(block.hash()));
        }
    }

}

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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                            )
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

    private record PeerIo(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output
    ) {
    }
}

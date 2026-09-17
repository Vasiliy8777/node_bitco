package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerConnection;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.GetDataMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                                connection,
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

    private static void runPeer(
            ServerSocket serverSocket,
            Block expectedBlock,
            Hash256 expectedHash
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
             * version from our Peer
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

            /*
             * Our modern feature negotiation.
             */
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

            /*
             * BlockSynchronizer must now request
             * exactly our expected block.
             */
            BitcoinMessage getDataWire =
                    reader.read(input)
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
                    expectedHash,
                    getData.inventory()
                            .get(0)
                            .hash()
            );

            /*
             * Send an unrelated post-handshake ping
             * before the requested block.
             *
             * BlockSynchronizer must delegate it to
             * Peer.handleMessage(), which sends pong.
             */
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

            /*
             * Finally send the requested block.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.block(
                                    new ru.bitcoin.node.p2p.message.BlockMessage(
                                            expectedBlock
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
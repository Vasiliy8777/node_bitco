package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PeerInventoryListenerTest {

    @Test void managedPeerDoesNotConsumeAnnouncementsBeforeListenersAreAttached() throws Exception {
        var hash = Hash256.fromDisplayHex("11".repeat(32));
        var received = new CompletableFuture<InvMessage>();
        try (var listener = new ServerSocket(0); var manager = new PeerManager()) {
            var remote = CompletableFuture.runAsync(() -> runInventoryPeer(listener, hash));
            try (var peer = new BitcoinClient(PARAMETERS).connectManaged("127.0.0.1", listener.getLocalPort(), 0)) {
                // Registration may be delayed arbitrarily: the socket retains the early INV and PING.
                assertTrue(peer.isReady());
                manager.addPeerListener(connected -> connected.addInventoryListener((source, inv) -> received.complete(inv)));
                manager.add(peer);
                assertEquals(hash, received.get(5, TimeUnit.SECONDS).inventory().getFirst().hash());
                remote.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final NetworkParameters PARAMETERS =
            NetworkParametersRegistry.regtest();

    private static final long LOCAL_NONCE =
            0x1122334455667788L;

    private static final long REMOTE_NONCE =
            0x8877665544332211L;

    @Test
    void shouldDispatchInvFromBackgroundReaderToListener()
            throws Exception {

        Hash256 announcedHash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                );

        CompletableFuture<InvMessage> received =
                new CompletableFuture<>();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runInventoryPeer(
                                    serverSocket,
                                    announcedHash
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
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.addInventoryListener(
                        (source, inventory) -> {

                            assertSame(
                                    peer,
                                    source
                            );

                            received.complete(
                                    inventory
                            );
                        }
                );

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                InvMessage inventory =
                        received.get(
                                5,
                                TimeUnit.SECONDS
                        );

                assertEquals(
                        1,
                        inventory.size()
                );

                InventoryVector vector =
                        inventory.inventory()
                                .get(0);

                assertEquals(
                        InventoryVector.MSG_BLOCK,
                        vector.type()
                );

                assertEquals(
                        announcedHash,
                        vector.hash()
                );

                server.get(
                        5,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    @Test
    void shouldNotNotifyRemovedInventoryListener()
            throws Exception {

        AtomicInteger calls =
                new AtomicInteger();
        CompletableFuture<Void> dispatched = new CompletableFuture<>();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runInventoryPeer(
                                    serverSocket,
                                    Hash256.fromDisplayHex(
                                            "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                                    )
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
                                 true,
                                 LOCAL_NONCE
                         )) {

                PeerInventoryListener listener =
                        (source, inventory) ->
                                calls.incrementAndGet();

                peer.addInventoryListener(
                        listener
                );

                peer.removeInventoryListener(
                        listener
                );
                peer.addInventoryListener((source, inventory) -> dispatched.complete(null));

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                server.get(
                        5,
                        TimeUnit.SECONDS
                );

                dispatched.get(5, TimeUnit.SECONDS);

                assertEquals(
                        0,
                        calls.get()
                );
            }
        }
    }

    @Test
    void failingInventoryListenerShouldNotPreventNextListener()
            throws Exception {

        AtomicInteger successfulCalls =
                new AtomicInteger();

        CompletableFuture<Void> received =
                new CompletableFuture<>();

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runInventoryPeer(
                                    serverSocket,
                                    Hash256.fromDisplayHex(
                                            "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                                    )
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
                                 true,
                                 LOCAL_NONCE
                         )) {

                peer.addInventoryListener(
                        (source, inventory) -> {
                            throw new IllegalStateException(
                                    "listener failure"
                            );
                        }
                );

                peer.addInventoryListener(
                        (source, inventory) -> {

                            successfulCalls.incrementAndGet();

                            received.complete(
                                    null
                            );
                        }
                );

                peer.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                peer.handshake();

                received.get(
                        5,
                        TimeUnit.SECONDS
                );

                assertEquals(
                        1,
                        successfulCalls.get()
                );

                // The server also requires PONG after INV: the reader must survive.
                server.get(
                        5,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    private static void runInventoryPeer(
            ServerSocket serverSocket,
            Hash256 announcedHash
    ) {

        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(
                    5_000
            );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
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

            /*
             * Receive client's VERSION.
             */
            BitcoinMessage clientVersion =
                    reader.read(
                            input
                    ).orElseThrow();

            assertEquals(
                    "version",
                    clientVersion.command()
            );

            /*
             * Send server VERSION.
             */
            VersionMessage remoteVersion =
                    new VersionMessage(
                            VersionMessage.CURRENT_PROTOCOL_VERSION,
                            VersionMessage.DEFAULT_SERVICES,
                            1_700_000_000L,
                            NetworkAddress.unspecified(),
                            NetworkAddress.unspecified(),
                            REMOTE_NONCE,
                            "/inventory-listener-test/",
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
             * Receive feature negotiation + VERACK
             * from our Peer.
             */
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

            /*
             * Complete the remote side of the handshake.
             */
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
             * Peer.handshake() now transitions to READY and
             * starts its existing PeerMessageReader.
             *
             * Announce one block through that same socket.
             */
            output.write(
                    encoder.encode(
                            BitcoinMessages.inv(
                                    new InvMessage(
                                            List.of(
                                                    new InventoryVector(
                                                            InventoryVector.MSG_BLOCK,
                                                            announcedHash
                                                    )
                                            )
                                    )
                            )
                    )
            );

            output.flush();

            output.write(encoder.encode(BitcoinMessages.ping(new PingMessage(42L))));
            output.flush();
            assertEquals("pong", reader.read(input).orElseThrow().command());

        } catch (Exception exception) {

            throw new RuntimeException(
                    exception
            );
        }
    }
}

package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerConnectionTest {

    @Test
    void shouldConnectSendAndReceiveBitcoinMessages()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> runServer(
                                    serverSocket
                            )
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 NetworkParametersRegistry.mainnet(),
                                 5_000,
                                 5_000
                         )) {

                connection.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertTrue(
                        connection.isConnected()
                );

                assertEquals(
                        serverSocket.getLocalPort(),
                        connection.remoteAddress()
                                .getPort()
                );

                connection.send(
                        new BitcoinMessage(
                                "ping",
                                new byte[]{
                                        1, 2, 3, 4,
                                        5, 6, 7, 8
                                }
                        )
                );

                BitcoinMessage response =
                        connection.receive()
                                .orElseThrow();

                assertEquals(
                        "pong",
                        response.command()
                );

                assertArrayEquals(
                        new byte[]{
                                1, 2, 3, 4,
                                5, 6, 7, 8
                        },
                        response.payload()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldReceiveMultipleMessagesFromPeer()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    BitcoinMessageEncoder encoder =
                                            new BitcoinMessageEncoder(
                                                    NetworkParametersRegistry
                                                            .mainnet()
                                            );

                                    BufferedOutputStream output =
                                            new BufferedOutputStream(
                                                    socket.getOutputStream()
                                            );

                                    output.write(
                                            encoder.encode(
                                                    BitcoinMessages.verack()
                                            )
                                    );

                                    output.write(
                                            encoder.encode(
                                                    new BitcoinMessage(
                                                            "ping",
                                                            new byte[]{
                                                                    10, 20, 30
                                                            }
                                                    )
                                            )
                                    );

                                    output.flush();

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 NetworkParametersRegistry.mainnet(),
                                 5_000,
                                 5_000
                         )) {

                connection.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                BitcoinMessage first =
                        connection.receive()
                                .orElseThrow();

                BitcoinMessage second =
                        connection.receive()
                                .orElseThrow();

                assertEquals(
                        "verack",
                        first.command()
                );

                assertEquals(
                        "ping",
                        second.command()
                );

                assertArrayEquals(
                        new byte[]{
                                10, 20, 30
                        },
                        second.payload()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldReturnEmptyWhenPeerClosesCleanly()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket ignored =
                                             serverSocket.accept()) {
                                    // Immediately close connection.
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 NetworkParametersRegistry.mainnet(),
                                 5_000,
                                 5_000
                         )) {

                connection.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                Optional<BitcoinMessage> result =
                        connection.receive();

                assertTrue(
                        result.isEmpty()
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldBecomeDisconnectedAfterClose()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    /*
                                     * Wait until the client closes
                                     * its side of the connection.
                                     */
                                    while (socket.getInputStream().read()
                                            != -1) {
                                        // consume
                                    }

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            PeerConnection connection =
                    new PeerConnection(
                            NetworkParametersRegistry.mainnet(),
                            5_000,
                            5_000
                    );

            connection.connect(
                    "127.0.0.1",
                    serverSocket.getLocalPort()
            );

            assertTrue(
                    connection.isConnected()
            );

            connection.close();

            assertFalse(
                    connection.isConnected()
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> connection.send(
                            BitcoinMessages.verack()
                    )
            );

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    @Test
    void shouldRejectSecondConnect()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    while (socket.getInputStream().read()
                                            != -1) {
                                        // consume
                                    }

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 NetworkParametersRegistry.mainnet(),
                                 5_000,
                                 5_000
                         )) {

                connection.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertThrows(
                        IllegalStateException.class,
                        () -> connection.connect(
                                "127.0.0.1",
                                serverSocket.getLocalPort()
                        )
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void runServer(
            ServerSocket serverSocket
    ) {
        try (Socket socket =
                     serverSocket.accept()) {

            socket.setSoTimeout(5_000);

            BitcoinMessageStreamReader reader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    NetworkParametersRegistry
                                            .mainnet()
                            )
                    );

            BitcoinMessageEncoder encoder =
                    new BitcoinMessageEncoder(
                            NetworkParametersRegistry
                                    .mainnet()
                    );

            BufferedInputStream input =
                    new BufferedInputStream(
                            socket.getInputStream()
                    );

            BufferedOutputStream output =
                    new BufferedOutputStream(
                            socket.getOutputStream()
                    );

            BitcoinMessage request =
                    reader.read(input)
                            .orElseThrow();

            if (!"ping".equals(
                    request.command()
            )) {
                throw new IllegalStateException(
                        "Expected ping"
                );
            }

            output.write(
                    encoder.encode(
                            new BitcoinMessage(
                                    "pong",
                                    request.payload()
                            )
                    )
            );

            output.flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Test
    void shouldRejectMessageFromDifferentBitcoinNetwork()
            throws Exception {

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    BitcoinMessageEncoder encoder =
                                            new BitcoinMessageEncoder(
                                                    NetworkParametersRegistry
                                                            .testnet()
                                            );

                                    socket.getOutputStream()
                                            .write(
                                                    encoder.encode(
                                                            BitcoinMessages
                                                                    .verack()
                                                    )
                                            );

                                    socket.getOutputStream()
                                            .flush();

                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                    );

            try (PeerConnection connection =
                         new PeerConnection(
                                 NetworkParametersRegistry.mainnet(),
                                 5_000,
                                 5_000
                         )) {

                connection.connect(
                        "127.0.0.1",
                        serverSocket.getLocalPort()
                );

                assertThrows(
                        java.io.IOException.class,
                        connection::receive
                );
            }

            server.get(
                    5,
                    TimeUnit.SECONDS
            );
        }
    }
}
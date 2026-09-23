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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerConnectionTest {

    @Test
    void closeInterruptsWriterWhenRemoteStopsReading() throws Exception {
        try (var listener = new ServerSocket()) {
            listener.setReceiveBufferSize(1024);
            listener.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            var accepted = CompletableFuture.supplyAsync(() -> {
                try { return listener.accept(); }
                catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
            });
            try (var connection = new PeerConnection(NetworkParametersRegistry.regtest())) {
                connection.connect("127.0.0.1", listener.getLocalPort());
                try (var remote = accepted.get(5, TimeUnit.SECONDS)) {
                    var started = new CountDownLatch(1);
                    var writer = CompletableFuture.runAsync(() -> {
                        var message = new BitcoinMessage("block", new byte[4_000_000]);
                        started.countDown();
                        try {
                            for (int i = 0; i < 100; i++) connection.send(message);
                            throw new AssertionError("Remote is not reading; writes should block");
                        } catch (java.io.IOException | IllegalStateException expected) { }
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(java.util.concurrent.TimeoutException.class, () -> writer.get(200, TimeUnit.MILLISECONDS));
                    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), connection::close);
                    writer.get(2, TimeUnit.SECONDS);
                }
            }
        }
    }

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

    @Test
    void shouldSerializeConcurrentSendsIntoCompleteBitcoinFrames()
            throws Exception {

        final int messagesPerSender =
                16;

        final int payloadSize =
                128 * 1024;

        try (ServerSocket serverSocket =
                     new ServerSocket(0)) {

            CompletableFuture<Void> server =
                    CompletableFuture.runAsync(
                            () -> {
                                try (Socket socket =
                                             serverSocket.accept()) {

                                    socket.setSoTimeout(
                                            5_000
                                    );

                                    BitcoinMessageStreamReader reader =
                                            new BitcoinMessageStreamReader(
                                                    new BitcoinMessageDecoder(
                                                            NetworkParametersRegistry
                                                                    .mainnet()
                                                    )
                                            );

                                    BufferedInputStream input =
                                            new BufferedInputStream(
                                                    socket.getInputStream()
                                            );

                                    boolean[][] received =
                                            new boolean[2][messagesPerSender];

                                    for (int i = 0;
                                         i < messagesPerSender * 2;
                                         i++) {

                                        BitcoinMessage message =
                                                reader.read(input)
                                                        .orElseThrow();

                                        assertEquals(
                                                "tx",
                                                message.command()
                                        );

                                        byte[] payload =
                                                message.payload();

                                        assertEquals(
                                                payloadSize,
                                                payload.length
                                        );

                                        int sender =
                                                Byte.toUnsignedInt(
                                                        payload[0]
                                                );

                                        int sequence =
                                                Byte.toUnsignedInt(
                                                        payload[1]
                                                );

                                        assertTrue(
                                                sender < 2
                                        );

                                        assertTrue(
                                                sequence < messagesPerSender
                                        );

                                        assertFalse(
                                                received[sender][sequence]
                                        );

                                        received[sender][sequence] =
                                                true;

                                        byte expected =
                                                (byte) (sender * 32
                                                        + sequence);

                                        for (int offset = 2;
                                             offset < payload.length;
                                             offset++) {

                                            assertEquals(
                                                    expected,
                                                    payload[offset]
                                            );
                                        }
                                    }

                                    for (boolean[] senderMessages : received) {
                                        for (boolean messageReceived : senderMessages) {
                                            assertTrue(
                                                    messageReceived
                                            );
                                        }
                                    }

                                } catch (Exception exception) {
                                    throw new RuntimeException(
                                            exception
                                    );
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

                CountDownLatch start =
                        new CountDownLatch(
                                1
                        );

                CompletableFuture<Void> firstSender =
                        CompletableFuture.runAsync(
                                () -> sendMessages(
                                        connection,
                                        0,
                                        messagesPerSender,
                                        payloadSize,
                                        start
                                )
                        );

                CompletableFuture<Void> secondSender =
                        CompletableFuture.runAsync(
                                () -> sendMessages(
                                        connection,
                                        1,
                                        messagesPerSender,
                                        payloadSize,
                                        start
                                )
                        );

                start.countDown();

                firstSender.get(
                        5,
                        TimeUnit.SECONDS
                );

                secondSender.get(
                        5,
                        TimeUnit.SECONDS
                );

                server.get(
                        5,
                        TimeUnit.SECONDS
                );
            }
        }
    }

    private static void sendMessages(
            PeerConnection connection,
            int sender,
            int messageCount,
            int payloadSize,
            CountDownLatch start
    ) {
        try {
            assertTrue(
                    start.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

            for (int sequence = 0;
                 sequence < messageCount;
                 sequence++) {

                byte[] payload =
                        new byte[payloadSize];

                payload[0] =
                        (byte) sender;

                payload[1] =
                        (byte) sequence;

                byte value =
                        (byte) (sender * 32
                                + sequence);

                java.util.Arrays.fill(
                        payload,
                        2,
                        payload.length,
                        value
                );

                connection.send(
                        new BitcoinMessage(
                                "tx",
                                payload
                        )
                );
            }

        } catch (Exception exception) {
            throw new RuntimeException(
                    exception
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

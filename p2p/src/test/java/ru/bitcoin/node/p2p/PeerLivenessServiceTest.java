package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.p2p.message.BitcoinMessages;
import ru.bitcoin.node.p2p.message.NetworkAddress;
import ru.bitcoin.node.p2p.message.PongMessage;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PeerLivenessServiceTest {

    private static final NetworkParameters PARAMETERS = NetworkParametersRegistry.mainnet();

    @Test
    void shouldMeasureRttAndKeepPeerWhenMatchingPongArrives() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(
                    () -> runPeer(serverSocket, true));

            PeerManager manager = new PeerManager();
            try (Peer peer = connectPeer(serverSocket);
                 PeerLivenessService service = new PeerLivenessService(
                         manager,
                         Duration.ofMillis(20),
                         Duration.ofMillis(250),
                         Duration.ofMillis(5),
                         new Random(1L))) {

                manager.add(peer, PeerConnectionRole.FULL_RELAY);
                service.start();

                waitUntil(() -> peer.lastPingRoundTrip().isPresent(), 2_000);

                assertTrue(peer.isReady());
                assertEquals(1, manager.size());
                assertTrue(peer.lastPingRoundTrip().orElseThrow().toNanos() >= 0L);
                assertTrue(peer.minPingRoundTrip().isPresent());
            } finally {
                manager.close();
            }

            server.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldCloseAndRemovePeerWhenPongDoesNotArrive() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(
                    () -> runPeer(serverSocket, false));

            PeerManager manager = new PeerManager();
            try (Peer peer = connectPeer(serverSocket);
                 PeerLivenessService service = new PeerLivenessService(
                         manager,
                         Duration.ofMillis(20),
                         Duration.ofMillis(80),
                         Duration.ofMillis(5),
                         new Random(2L))) {

                manager.add(peer, PeerConnectionRole.FULL_RELAY);
                service.start();

                waitUntil(manager::isEmpty, 2_000);

                assertEquals(PeerState.CLOSED, peer.state());
                assertTrue(manager.isEmpty());
            } finally {
                manager.close();
            }

            server.get(5, TimeUnit.SECONDS);
        }
    }

    private static Peer connectPeer(ServerSocket serverSocket) throws Exception {
        PeerConnection connection = new PeerConnection(PARAMETERS, 5_000, 5_000);
        Peer peer = new Peer(connection, VersionMessage.DEFAULT_SERVICES, 0, true);
        try {
            peer.connect("127.0.0.1", serverSocket.getLocalPort());
            peer.handshake(false);
            return peer;
        } catch (Exception e) {
            peer.close();
            throw e;
        }
    }

    private static void runPeer(ServerSocket serverSocket, boolean answerPing) {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(5_000);
            PeerIo io = peerIo(socket);
            completeHandshake(io);

            BitcoinMessage ping = io.reader().read(io.input()).orElseThrow();
            assertEquals("ping", ping.command());

            if (answerPing) {
                long nonce = BitcoinMessages.decodePing(ping).nonce();
                io.output().write(io.encoder().encode(
                        BitcoinMessages.pong(new PongMessage(nonce))));
                io.output().flush();
            }

            assertEquals(-1, io.input().read());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void completeHandshake(PeerIo io) throws Exception {
        assertEquals("version", io.reader().read(io.input()).orElseThrow().command());

        VersionMessage remoteVersion = new VersionMessage(
                VersionMessage.CURRENT_PROTOCOL_VERSION,
                VersionMessage.DEFAULT_SERVICES,
                1_700_000_000L,
                NetworkAddress.unspecified(),
                NetworkAddress.unspecified(),
                0x1122334455667788L,
                "/liveness-test/",
                0,
                true);

        io.output().write(io.encoder().encode(BitcoinMessages.version(remoteVersion)));
        io.output().flush();

        assertEquals("wtxidrelay", io.reader().read(io.input()).orElseThrow().command());
        assertEquals("sendaddrv2", io.reader().read(io.input()).orElseThrow().command());

        assertEquals("sendcmpct", io.reader().read(io.input()).orElseThrow().command());
        assertEquals("verack", io.reader().read(io.input()).orElseThrow().command());

        io.output().write(io.encoder().encode(BitcoinMessages.wtxidRelay()));
        io.output().write(io.encoder().encode(BitcoinMessages.sendAddrV2()));
        io.output().write(io.encoder().encode(BitcoinMessages.verack()));
        io.output().flush();
    }

    private static PeerIo peerIo(Socket socket) throws Exception {
        return new PeerIo(
                new BitcoinMessageStreamReader(new BitcoinMessageDecoder(PARAMETERS)),
                new BitcoinMessageEncoder(PARAMETERS),
                new BufferedInputStream(socket.getInputStream()),
                new BufferedOutputStream(socket.getOutputStream()));
    }

    private static void waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.value()) return;
            Thread.sleep(5L);
        }
        fail("Condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Check { boolean value() throws Exception; }

    private record PeerIo(
            BitcoinMessageStreamReader reader,
            BitcoinMessageEncoder encoder,
            BufferedInputStream input,
            BufferedOutputStream output) {}
}

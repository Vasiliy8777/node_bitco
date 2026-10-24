package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnBlockClientAndBudgetTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 4, 16})
    void configuredWindowRefillsAndMatchesOutOfOrderReplies(int window) throws Exception {
        var expected = new ArrayList<RvnBlockClient.Expected>();
        for (int i = 1; i <= 20; i++) {
            byte[] raw = new byte[80]; raw[0] = (byte) i;
            expected.add(new RvnBlockClient.Expected(RvnWire.doubleSha(raw), RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME)));
        }
        var error = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    handshake(socket, server.getLocalPort());
                    for (int offset = 0; offset < expected.size(); offset += window) {
                        int end = Math.min(offset + window, expected.size());
                        for (int i = offset; i < end; i++) {
                            var request = RvnWire.read(socket.getInputStream(), RvnNetwork.REGTEST);
                            assertEquals("getdata", request.command());
                            assertArrayEquals(expected.get(i).id(), Arrays.copyOfRange(request.payload(), 5, 37));
                        }
                        for (int i = end - 1; i >= offset; i--)
                            RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST,
                                    new RvnMessage("block", Arrays.copyOf(expected.get(i).header().serialize(), 81)));
                    }
                    while (socket.getInputStream().read() != -1) { }
                } catch (Throwable failure) { error.set(failure); }
            });
            try (var session = RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 1000, 32)) {
                var received = new HashSet<Integer>();
                RvnBlockClient.requestBatch(session, expected, 3000, window, (item, payload) -> assertTrue(received.add(item.header().version())));
                assertEquals(20, received.size());
            }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }

    @Test void localValidationTimeDoesNotExpireAnAlreadyReceivedPipelinedBlock() throws Exception {
        var expected = new ArrayList<RvnBlockClient.Expected>();
        for (int i = 1; i <= 2; i++) {
            byte[] raw = new byte[80]; raw[0] = (byte) i;
            expected.add(new RvnBlockClient.Expected(RvnWire.doubleSha(raw), RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME)));
        }
        var error = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    handshake(socket, server.getLocalPort());
                    for (int i = 0; i < 2; i++) RvnWire.read(socket.getInputStream(), RvnNetwork.REGTEST);
                    for (var item : expected) RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST,
                            new RvnMessage("block", Arrays.copyOf(item.header().serialize(), 81)));
                    while (socket.getInputStream().read() != -1) { }
                } catch (Throwable failure) { error.set(failure); }
            });
            try (var session = RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 1000, 8)) {
                var received = new AtomicInteger();
                RvnBlockClient.requestBatch(session, expected, 500, 2, (item, payload) -> {
                    if (received.incrementAndGet() == 1) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(900));
                });
                assertEquals(2, received.get());
            }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }
    @Test void pipelineAcceptsOutOfOrderRepliesWithoutWaitingForEachRoundTrip() throws Exception {
        var expected = new ArrayList<RvnBlockClient.Expected>();
        for (int i = 1; i <= 5; i++) {
            byte[] raw = new byte[80], id = new byte[32]; raw[0] = (byte) i; id[0] = (byte) i;
            expected.add(new RvnBlockClient.Expected(id, RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME)));
        }
        var error = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    handshake(socket, server.getLocalPort());
                    for (int i = 0; i < 4; i++) assertEquals("getdata", RvnWire.read(socket.getInputStream(), RvnNetwork.REGTEST).command());
                    for (int i = 3; i >= 0; i--) RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST,
                            new RvnMessage("block", Arrays.copyOf(expected.get(i).header().serialize(), 81)));
                    assertEquals("getdata", RvnWire.read(socket.getInputStream(), RvnNetwork.REGTEST).command());
                    RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST,
                            new RvnMessage("block", Arrays.copyOf(expected.get(4).header().serialize(), 81)));
                    while (socket.getInputStream().read() != -1) { }
                } catch (Throwable failure) { error.set(failure); }
            });
            try (var session = RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 1000, 16)) {
                var received = new ArrayList<Integer>();
                RvnBlockClient.requestBatch(session, expected, 3000, (item, payload) -> received.add(item.header().version()));
                assertEquals(List.of(4, 3, 2, 1, 5), received);
            }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }
    private static void handshake(Socket socket, int port) throws Exception {
        socket.setSoTimeout(8000);
        var in = socket.getInputStream(); var out = socket.getOutputStream();
        assertEquals("version", RvnWire.read(in, RvnNetwork.REGTEST).command());
        RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("version", RvnVersion.outbound(70028, port, 0)));
        assertEquals("verack", RvnWire.read(in, RvnNetwork.REGTEST).command());
        RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("verack", new byte[0]));
    }

    @Test
    void largeMessagesAreBoundedByBytesBeforeMessageCountLimit() throws Exception {
        var error = new AtomicReference<Throwable>(); var closed = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var peer = Thread.ofPlatform().daemon(true).start(() -> {
                try (var socket = server.accept()) {
                    handshake(socket, server.getLocalPort());
                    var message = new RvnMessage("block", new byte[RvnWire.MAX_PAYLOAD]);
                    for (int i = 0; i < 5; i++) RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST, message);
                    while (socket.getInputStream().read() != -1) { }
                } catch (Throwable failure) { if (!(failure instanceof IOException)) error.set(failure); }
                finally { closed.countDown(); }
            });
            try (var session = RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 2000, 16)) {
                assertTrue(closed.await(12, TimeUnit.SECONDS));
                assertTrue(session.isClosed()); assertNotNull(session.failure());
                assertTrue(session.failure().getMessage().contains("byte/count limit"));
                assertEquals(4, session.queuedMessages());
            }
            peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
        }
    }

    @Test
    void wrongHeaderAndNotfoundCannotSatisfyBlockRequest() throws Exception {
        for (String command : new String[]{"block", "notfound"}) for (boolean batch : new boolean[]{false, true}) {
            var error = new AtomicReference<Throwable>();
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                var peer = Thread.ofPlatform().daemon(true).start(() -> {
                    try (var socket = server.accept()) {
                        handshake(socket, server.getLocalPort());
                        assertEquals("getdata", RvnWire.read(socket.getInputStream(), RvnNetwork.REGTEST).command());
                        byte[] payload = command.equals("block") ? new byte[81] : new byte[]{0};
                        if (command.equals("block")) payload[0] = 9;
                        RvnWire.write(socket.getOutputStream(), RvnNetwork.REGTEST, new RvnMessage(command, payload));
                        while (socket.getInputStream().read() != -1) { }
                    } catch (Throwable failure) { error.set(failure); }
                });
                try (var session = RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 1000, 8)) {
                    var expected = RvnBlockHeader.parse(new byte[80], RvnActivation.MAINNET_KAWPOW_TIME);
                    assertThrows(IOException.class, () -> {
                        if (batch) RvnBlockClient.requestBatch(session, List.of(new RvnBlockClient.Expected(new byte[32], expected)),
                                2000, (item, body) -> fail("Bad block must not reach storage"));
                        else RvnBlockClient.request(session, new byte[32], expected, 2000);
                    });
                }
                peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
            }
        }
    }
}

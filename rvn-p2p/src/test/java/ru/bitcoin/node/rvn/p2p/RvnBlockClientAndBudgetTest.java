package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnBlockClientAndBudgetTest {
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
        for (String command : new String[]{"block", "notfound"}) {
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
                    assertThrows(IOException.class, () -> RvnBlockClient.request(session, new byte[32], expected, 2000));
                }
                peer.join(3000); assertFalse(peer.isAlive()); if (error.get() != null) fail(error.get());
            }
        }
    }
}

package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.RvnMessage;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.protocol.RvnVersion;
import ru.bitcoin.node.rvn.protocol.RvnWire;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RvnPeerSessionTest {
    @Test
    void persistentSessionReceivesMessageAndAnswersPing() throws Exception {
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var in = socket.getInputStream();
                    var out = socket.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST,
                            new RvnMessage("version", RvnVersion.outbound(70028, 18444, 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("verack", new byte[0]));
                    byte[] nonce = {1, 2, 3, 4, 5, 6, 7, 8};
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("ping", nonce));
                    assertArrayEquals(nonce, RvnWire.read(in, RvnNetwork.REGTEST).payload());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("inv", new byte[]{42}));
                    // Wait for the client to close before ending the server thread.
                    while (in.read() != -1) { }
                } catch (Throwable e) { serverError.set(e); }
            });
            peer.start();
            try (RvnPeerSession session = RvnPeerSession.connect(
                    RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 3000, 4)) {
                assertEquals(70028, session.peerVersion());
                RvnMessage message = session.poll(2500);
                assertNotNull(message);
                assertEquals("inv", message.command());
                assertArrayEquals(new byte[]{42}, message.payload());
            }
            peer.join(4000);
            assertFalse(peer.isAlive());
            if (serverError.get() != null) fail(serverError.get());
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", 18444, 1000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RvnPeerSession.connect(RvnNetwork.REGTEST, "127.0.0.1", 18444, 1000, 4097));
    }
}

package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RvnHeadersClientBoundedTest {
    @Test
    void ignoresLimitedNoiseAndAcceptsHeaders() throws Exception {
        runPeer(3, false);
    }

    @Test
    void rejectsExcessiveNoiseBeforeHeaders() throws Exception {
        runPeer(RvnHeadersClient.MAX_IGNORED_MESSAGES + 1, true);
    }

    private static void runPeer(int noise, boolean reject) throws Exception {
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
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
                    var request = RvnWire.read(in, RvnNetwork.REGTEST);
                    assertEquals("getheaders", request.command());
                    assertEquals(70028, RvnGetHeaders.decode(request.payload()).protocolVersion());
                    for (int i = 0; i < noise; i++) {
                        RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("inv", new byte[0]));
                    }
                    if (!reject)
                        RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("headers", new byte[]{0}));
                } catch (IOException e) {
                    // An early client close is expected after the noise limit is reached.
                    if (!reject) peerFailure.set(e);
                } catch (Throwable e) {
                    peerFailure.set(e);
                }
            }, "rvn-noisy-test-peer");
            peer.start();
            try (RvnPeerSession session = RvnPeerSession.connect(RvnNetwork.REGTEST,
                    "127.0.0.1", server.getLocalPort(), 3000, 128)) {
                if (reject)
                    assertThrows(IOException.class, () -> RvnHeadersClient.request(session, 70028,
                            List.of(new byte[32]), new byte[32], 0, 3000));
                else
                    assertTrue(RvnHeadersClient.request(session, 70028,
                            List.of(new byte[32]), new byte[32], 0, 3000).isEmpty());
            } finally {
                peer.join(4000);
            }
            assertFalse(peer.isAlive());
            if (peerFailure.get() != null) fail(peerFailure.get());
        }
    }
}

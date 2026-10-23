package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RvnHeadersClientTest {
    @Test void localPeerAnswersGetheaders() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(3000);
                    var in = s.getInputStream(); var out = s.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("version", RvnVersion.outbound(70028, 18444, 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("verack", new byte[0]));
                    assertEquals("getheaders", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("headers", new byte[]{0}));
                    while (in.read() != -1) { }
                } catch (Throwable t) { failure.set(t); }
            }, "rvn-test-peer");
            peer.start();
            try (RvnPeerSession session = RvnPeerSession.connect(RvnNetwork.REGTEST,
                    "127.0.0.1", server.getLocalPort(), 3000, 8)) {
                assertTrue(RvnHeadersClient.request(session, 70028,
                        List.of(new byte[32]), new byte[32], 0, 2000).isEmpty());
            } finally { peer.join(4000); }
            assertFalse(peer.isAlive());
            if (failure.get() != null) fail(failure.get());
        }
    }
}

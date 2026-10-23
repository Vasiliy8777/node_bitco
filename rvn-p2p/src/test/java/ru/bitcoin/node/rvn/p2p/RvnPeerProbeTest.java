package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;

import java.net.*;
import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnPeerProbeTest {
    @Test
    void loopbackHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket sock = server.accept()) {
                    sock.setSoTimeout(3000);
                    var in = sock.getInputStream();
                    var out = sock.getOutputStream();
                    assertEquals("version", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("version", RvnVersion.outbound(70028, server.getLocalPort(), 0)));
                    assertEquals("verack", RvnWire.read(in, RvnNetwork.REGTEST).command());
                    RvnWire.write(out, RvnNetwork.REGTEST, new RvnMessage("verack", new byte[0]));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            peer.start();
            var result = new RvnPeerProbe().probe(RvnNetwork.REGTEST, "127.0.0.1", server.getLocalPort(), 3000);
            assertEquals(70028, result.peerVersion());
            assertTrue(result.verack());
            peer.join(3000);
            assertFalse(peer.isAlive());
        }
    }
}

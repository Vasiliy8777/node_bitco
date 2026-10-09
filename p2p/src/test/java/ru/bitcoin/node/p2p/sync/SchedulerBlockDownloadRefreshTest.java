package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerBlockDownloadRefreshTest {
    @Test
    void slowAncestryRefreshDoesNotBlockSessionReadsOrClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var peers = new PeerManager();
             var session = new SchedulerBlockDownloadSession(peers, new BlockDownloadService(peers),
                     new BlockDownloadTimeoutPolicy(Duration.ofMinutes(10)))) {
            session.peerPolicy(new BlockDownloadPeerPolicy() {
                public boolean canServe(Peer peer, Hash256 hash, Long height) { return true; }
                public void refresh(List<Peer> snapshot) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Refresh was not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
            });
            var polling = CompletableFuture.runAsync(() -> {
                try {
                    session.pollCompleted(Duration.ZERO);
                    fail("Closed session must reject polling");
                } catch (java.io.IOException expected) {
                    // Close proceeds while the persistent lookup is still blocked.
                    assertEquals("Block download session is closed", expected.getMessage());
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var observation = CompletableFuture.runAsync(() -> {
                    assertEquals(0, session.pendingCount());
                    assertTrue(session.inFlightPeer(new Hash256(new byte[32])).isEmpty());
                    session.close();
                });
                observation.get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            polling.get(2, TimeUnit.SECONDS);
        }
    }
}

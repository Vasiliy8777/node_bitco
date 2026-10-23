package ru.bitcoin.node.rvn.p2p;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RvnPeerBackoffTest {
    @Test
    void exponentialBackoffAndReset() {
        var peer = new RvnPeerEndpoint("127.0.0.1", 8767);
        var policy = new RvnPeerBackoff(100, 250);
        assertTrue(policy.ready(peer, 0));
        policy.failure(peer, 0);
        assertFalse(policy.ready(peer, TimeUnit.MILLISECONDS.toNanos(99)));
        assertTrue(policy.ready(peer, TimeUnit.MILLISECONDS.toNanos(100)));
        policy.failure(peer, 0);
        assertEquals(200, policy.remainingMillis(peer, 0));
        policy.failure(peer, 0);
        assertEquals(250, policy.remainingMillis(peer, 0));
        policy.success(peer);
        assertEquals(0, policy.trackedPeers());
        assertTrue(policy.ready(peer, 0));
    }

    @Test
    void poolIsBoundedAndUnique() {
        var pool = new RvnPeerPool(2);
        var a = new RvnPeerEndpoint("localhost", 1);
        var b = new RvnPeerEndpoint("localhost", 2);
        assertTrue(pool.add(a));
        assertFalse(pool.add(a));
        assertTrue(pool.add(b));
        assertFalse(pool.add(new RvnPeerEndpoint("localhost", 3)));
        assertEquals(2, pool.snapshot().size());
        assertThrows(UnsupportedOperationException.class, () -> pool.snapshot().clear());
        assertTrue(pool.remove(a));
        assertEquals(1, pool.size());
    }
}

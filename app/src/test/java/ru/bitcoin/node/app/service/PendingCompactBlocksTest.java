package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.CompactBlockReconstruction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PendingCompactBlocksTest {
    private final CompactBlockReconstruction.Partial partial = mock(CompactBlockReconstruction.Partial.class);
    private static Hash256 hash(int value) {
        byte[] bytes = new byte[32]; bytes[0] = (byte) value; return new Hash256(bytes);
    }

    @Test
    void enforcesPeerAndGlobalBytesAndReleasesOnResponseAndDisconnect() {
        var pending = new PendingCompactBlocks(100, 150, 16);
        var a = mock(Peer.class); var b = mock(Peer.class); var c = mock(Peer.class);
        pending.register(a); pending.register(b); pending.register(c);
        assertEquals(PendingCompactBlocks.Admission.STORED, pending.add(a, hash(1), partial, 100, 90));
        assertEquals(PendingCompactBlocks.Admission.REJECTED, pending.add(a, hash(2), partial, 100, 11));
        assertEquals(PendingCompactBlocks.Admission.STORED, pending.add(b, hash(2), partial, 100, 60));
        assertEquals(PendingCompactBlocks.Admission.REJECTED, pending.add(c, hash(3), partial, 100, 1));
        assertEquals(150, pending.retainedBytes());
        assertSame(partial, pending.take(a, hash(1)).partial());
        assertNull(pending.take(a, hash(1)));
        assertEquals(60, pending.retainedBytes());
        assertEquals(PendingCompactBlocks.Admission.STORED, pending.add(c, hash(3), partial, 100, 90));
        pending.removePeer(b); pending.removePeer(b);
        assertEquals(90, pending.retainedBytes());
        assertEquals(PendingCompactBlocks.Admission.REJECTED, pending.add(b, hash(4), partial, 100, 1));
        pending.clear();
        assertEquals(0, pending.retainedBytes());
    }

    @Test
    void duplicatesCannotReplaceStateOrExtendDeadline() {
        var pending = new PendingCompactBlocks(100, 100, 16);
        var peer = mock(Peer.class); pending.register(peer);
        pending.add(peer, hash(1), partial, 10, 50);
        assertEquals(PendingCompactBlocks.Admission.DUPLICATE,
                pending.add(peer, hash(1), mock(CompactBlockReconstruction.Partial.class), 1000, 99));
        assertEquals(50, pending.retainedBytes());
        assertTrue(pending.expire(9).isEmpty());
        assertEquals(java.util.List.of(new PendingCompactBlocks.Expired(peer, hash(1))), pending.expire(10));
        assertEquals(0, pending.retainedBytes());
        assertFalse(pending.contains(peer, hash(1)));
        assertTrue(pending.expire(1001).isEmpty());
    }

    @Test
    void countLimitRejectsNewWorkWithoutEvictingExistingRequest() {
        var pending = new PendingCompactBlocks(100, 100, 1);
        var peer = mock(Peer.class); pending.register(peer);
        pending.add(peer, hash(1), partial, 100, 1);
        assertEquals(PendingCompactBlocks.Admission.REJECTED, pending.add(peer, hash(2), partial, 100, 1));
        assertTrue(pending.contains(peer, hash(1)));
        assertSame(partial, pending.take(peer, hash(1)).partial());
        assertEquals(PendingCompactBlocks.Admission.STORED, pending.add(peer, hash(2), partial, 100, 1));
    }

    @Test
    void expiryWorksAcrossMonotonicClockWraparound() {
        var pending = new PendingCompactBlocks(100, 100, 1);
        var peer = mock(Peer.class); pending.register(peer);
        long start = Long.MAX_VALUE - 5;
        pending.add(peer, hash(1), partial, start + 10, 10);
        assertTrue(pending.expire(start + 9).isEmpty());
        assertEquals(1, pending.expire(start + 10).size());
        assertEquals(0, pending.retainedBytes());
    }
}
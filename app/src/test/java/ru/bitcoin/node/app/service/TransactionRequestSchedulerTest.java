package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.InventoryVector;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TransactionRequestSchedulerTest {

    @Test
    void retainsAnnouncementsBeyondGlobalInFlightLimitAndRefillsCapacity() {
        var scheduler = new TransactionRequestScheduler();
        var peer = mock(Peer.class);
        var hashes = new ArrayList<Hash256>();

        for (int i = 0; i < 1_500; i++) {
            Hash256 hash = hash(i);
            hashes.add(hash);
            scheduler.announced(peer, new InventoryVector(InventoryVector.MSG_TX, hash));
        }

        var first = scheduler.schedule(1L, ignored -> true);
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT, first.size());
        assertEquals(1_500, scheduler.candidateCount());
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT, scheduler.inFlightCount());

        scheduler.notFound(peer, hashes.getFirst());
        var refill = scheduler.schedule(2L, ignored -> true);
        assertEquals(1, refill.size());
        assertEquals(hashes.get(TransactionRequestScheduler.MAX_IN_FLIGHT), refill.getFirst().vector().hash());
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT, scheduler.inFlightCount());
    }

    @Test
    void retriesAnotherAnnouncingPeerAfterNotFound() {
        var scheduler = new TransactionRequestScheduler();
        var firstPeer = mock(Peer.class);
        var secondPeer = mock(Peer.class);
        Hash256 hash = hash(7);
        var vector = new InventoryVector(InventoryVector.MSG_TX, hash);

        scheduler.announced(firstPeer, vector);
        scheduler.announced(secondPeer, vector);

        var first = scheduler.schedule(10L, ignored -> true);
        assertEquals(1, first.size());
        assertSame(firstPeer, first.getFirst().peer());

        scheduler.notFound(firstPeer, hash);
        var retry = scheduler.schedule(11L, ignored -> true);
        assertEquals(1, retry.size());
        assertSame(secondPeer, retry.getFirst().peer());
        assertEquals(hash, retry.getFirst().vector().hash());
    }

    @Test
    void retriesAnotherPeerAfterRequestTimeout() {
        var scheduler = new TransactionRequestScheduler();
        var firstPeer = mock(Peer.class);
        var secondPeer = mock(Peer.class);
        Hash256 hash = hash(11);
        var vector = new InventoryVector(5, hash);

        scheduler.announced(firstPeer, vector);
        scheduler.announced(secondPeer, vector);
        assertSame(firstPeer, scheduler.schedule(100L, ignored -> true).getFirst().peer());

        long afterTimeout = 100L + TransactionRequestScheduler.REQUEST_TIMEOUT_NANOS + 1L;
        var retry = scheduler.schedule(afterTimeout, ignored -> true);
        assertEquals(1, retry.size());
        assertSame(secondPeer, retry.getFirst().peer());
        assertEquals(5, retry.getFirst().vector().type());
    }

    @Test
    void neverRequestsSameHashFromTwoPeersAtOnce() {
        var scheduler = new TransactionRequestScheduler();
        var firstPeer = mock(Peer.class);
        var secondPeer = mock(Peer.class);
        Hash256 hash = hash(21);

        scheduler.announced(firstPeer, new InventoryVector(InventoryVector.MSG_TX, hash));
        scheduler.announced(secondPeer, new InventoryVector(InventoryVector.MSG_TX, hash));

        assertEquals(1, scheduler.schedule(1L, ignored -> true).size());
        assertTrue(scheduler.schedule(2L, ignored -> true).isEmpty());
        assertEquals(1, scheduler.inFlightCount());
    }

    private static Hash256 hash(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        bytes[1] = (byte) (value >>> 8);
        bytes[2] = (byte) (value >>> 16);
        bytes[3] = (byte) (value >>> 24);
        return new Hash256(bytes);
    }
}

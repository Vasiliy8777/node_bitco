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
    void sharesFullWindowAndRotatesAcrossSingleSlotRefills() {
        var scheduler = new TransactionRequestScheduler();
        var peers = new ArrayList<Peer>();
        for (int p = 0; p < 12; p++) {
            Peer peer = mock(Peer.class);
            peers.add(peer);
            for (int i = 0; i < 200; i++) {
                scheduler.announced(peer, new InventoryVector(InventoryVector.MSG_TX, hash(p * 200 + i)), 0L);
            }
        }
        var active = new ArrayList<>(scheduler.schedule(1L, ignored -> true));
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT, active.size());
        for (Peer peer : peers) {
            long count = active.stream().filter(request -> request.peer() == peer).count();
            assertTrue(count == 85 || count == 86, "Every peer must share the initial window");
        }
        var served = new java.util.HashSet<Peer>();
        for (int i = 0; i < peers.size(); i++) {
            scheduler.forget(active.get(i).vector().hash());
            var refill = scheduler.schedule(2L + i, ignored -> true);
            assertEquals(1, refill.size());
            assertTrue(served.add(refill.getFirst().peer()), "Refills must rotate between peers");
            assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT, scheduler.inFlightCount());
        }
        assertEquals(peers.size(), served.size());
    }

    @Test
    void newlyAnnouncingPeerGetsFreedSlotWithinOneRotation() {
        var scheduler = new TransactionRequestScheduler();
        for (int p = 0; p < 8; p++) {
            Peer peer = mock(Peer.class);
            for (int i = 0; i < 200; i++) {
                scheduler.announced(peer, new InventoryVector(5, hash(p * 200 + i)), 0L);
            }
        }
        var active = scheduler.schedule(1L, ignored -> true);
        var newcomer = mock(Peer.class);
        scheduler.announced(newcomer, new InventoryVector(5, hash(10_000)), 2L);
        assertTrue(scheduler.schedule(2L, ignored -> true).isEmpty());
        // Existing peers can refill, but the newcomer must be served within one rotation.
        boolean served = false;
        for (int i = 0; i < 9 && !served; i++) {
            scheduler.forget(active.get(i).vector().hash());
            var refill = scheduler.schedule(3L + i, ignored -> true);
            assertEquals(1, refill.size());
            served = refill.getFirst().peer() == newcomer;
        }
        assertTrue(served);
    }

    @Test
    void sharedAnnouncementsAreDownloadedOnceWithoutBlockingIndependentWork() {
        var scheduler = new TransactionRequestScheduler();
        var first = mock(Peer.class);
        var second = mock(Peer.class);
        for (int i = 0; i < 128; i++) {
            var vector = new InventoryVector(InventoryVector.MSG_TX, hash(i));
            scheduler.announced(first, vector, 0L);
            scheduler.announced(second, vector, 0L);
        }
        for (int i = 128; i < 192; i++) {
            scheduler.announced(second, new InventoryVector(5, hash(i)), 0L);
        }
        var requests = scheduler.schedule(1L, ignored -> true);
        assertEquals(192, requests.size());
        assertEquals(192, requests.stream().map(request -> request.vector().hash()).distinct().count());
        assertEquals(128, requests.stream().filter(request -> request.peer() == second).count());
        assertTrue(scheduler.schedule(2L, ignored -> true).isEmpty());
    }

    @Test
    void floodingPeerCannotConsumeOtherPeersDownloadCapacity() {
        var scheduler = new TransactionRequestScheduler();
        var flooder = mock(Peer.class);
        var honest = mock(Peer.class);
        for (int i = 0; i < 50_000; i++) {
            scheduler.announced(flooder, new InventoryVector(InventoryVector.MSG_TX, hash(i)), 0L);
        }
        assertEquals(TransactionRequestScheduler.MAX_CANDIDATES_PER_PEER, scheduler.candidateCount());
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT_PER_PEER,
                scheduler.schedule(1L, ignored -> true).size());
        scheduler.announced(honest, new InventoryVector(InventoryVector.MSG_TX, hash(100_000)), 1L);
        assertSame(honest, scheduler.schedule(2L, ignored -> true).getFirst().peer());
        scheduler.schedule(3L, peer -> peer == honest);
        assertEquals(1, scheduler.candidateCount());
        assertEquals(1, scheduler.inFlightCount());
        scheduler.announced(flooder, new InventoryVector(InventoryVector.MSG_TX, hash(200_000)), 4L);
        assertSame(flooder, scheduler.schedule(4L, ignored -> true).getFirst().peer());
    }

    @Test
    void globalBudgetIsBoundedAndExpiredAnnouncementsReleasePeerBudgets() {
        var scheduler = new TransactionRequestScheduler();
        var peers = new ArrayList<Peer>();
        for (int p = 0; p < 10; p++) {
            var peer = mock(Peer.class);
            peers.add(peer);
            for (int i = 0; i < 2_048; i++) {
                scheduler.announced(peer, new InventoryVector(InventoryVector.MSG_TX, hash(p * 2_048 + i)), 0L);
            }
        }
        assertEquals(TransactionRequestScheduler.MAX_CANDIDATES, scheduler.candidateCount());
        long expired = TransactionRequestScheduler.ANNOUNCEMENT_TTL_NANOS;
        // Reannouncements must not keep the original entries alive forever.
        scheduler.announced(peers.getFirst(), new InventoryVector(InventoryVector.MSG_TX, hash(0)), expired - 1);
        assertTrue(scheduler.schedule(expired, ignored -> true).isEmpty());
        assertEquals(0, scheduler.candidateCount());
        scheduler.announced(peers.getFirst(), new InventoryVector(InventoryVector.MSG_TX, hash(0)), expired);
        assertEquals(1, scheduler.schedule(expired, ignored -> true).size());
    }

    @Test
    void limitsSourcesAndReleasesTheirBudgetsAfterCompletion() {
        var scheduler = new TransactionRequestScheduler();
        var peers = new ArrayList<Peer>();
        var vector = new InventoryVector(5, hash(0));
        for (int i = 0; i < 20; i++) {
            var peer = mock(Peer.class);
            peers.add(peer);
            scheduler.announced(peer, vector, 0L);
        }
        for (int i = 0; i < TransactionRequestScheduler.MAX_SOURCES_PER_HASH; i++) {
            var requests = scheduler.schedule(1L, ignored -> true);
            assertEquals(1, requests.size());
            assertSame(peers.get(i), requests.getFirst().peer());
            scheduler.notFound(peers.get(i), hash(0));
        }
        assertTrue(scheduler.schedule(1L, ignored -> true).isEmpty());
        assertEquals(0, scheduler.candidateCount());
        var peer = peers.getFirst();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < TransactionRequestScheduler.MAX_CANDIDATES_PER_PEER; i++) {
                scheduler.announced(peer, new InventoryVector(5, hash(i)), 0L);
            }
            assertEquals(TransactionRequestScheduler.MAX_CANDIDATES_PER_PEER, scheduler.candidateCount());
            for (int i = 0; i < TransactionRequestScheduler.MAX_CANDIDATES_PER_PEER; i++) scheduler.forget(hash(i));
        }
    }

    @Test
    void retainsAnnouncementsBeyondPeerInFlightLimitAndRefillsCapacity() {
        var scheduler = new TransactionRequestScheduler();
        var peer = mock(Peer.class);
        var hashes = new ArrayList<Hash256>();

        for (int i = 0; i < 1_500; i++) {
            Hash256 hash = hash(i);
            hashes.add(hash);
            scheduler.announced(peer, new InventoryVector(InventoryVector.MSG_TX, hash), 0L);
        }

        var first = scheduler.schedule(1L, ignored -> true);
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT_PER_PEER, first.size());
        assertEquals(1_500, scheduler.candidateCount());
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT_PER_PEER, scheduler.inFlightCount());

        scheduler.notFound(peer, hashes.getFirst());
        var refill = scheduler.schedule(2L, ignored -> true);
        assertEquals(1, refill.size());
        assertEquals(hashes.get(TransactionRequestScheduler.MAX_IN_FLIGHT_PER_PEER), refill.getFirst().vector().hash());
        assertEquals(TransactionRequestScheduler.MAX_IN_FLIGHT_PER_PEER, scheduler.inFlightCount());
    }

    @Test
    void retriesAnotherAnnouncingPeerAfterNotFound() {
        var scheduler = new TransactionRequestScheduler();
        var firstPeer = mock(Peer.class);
        var secondPeer = mock(Peer.class);
        Hash256 hash = hash(7);
        var vector = new InventoryVector(InventoryVector.MSG_TX, hash);

        scheduler.announced(firstPeer, vector, 0L);
        scheduler.announced(secondPeer, vector, 0L);

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

        scheduler.announced(firstPeer, vector, 0L);
        scheduler.announced(secondPeer, vector, 0L);
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

        scheduler.announced(firstPeer, new InventoryVector(InventoryVector.MSG_TX, hash), 0L);
        scheduler.announced(secondPeer, new InventoryVector(InventoryVector.MSG_TX, hash), 0L);

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

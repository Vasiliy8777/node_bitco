package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerHeaderRequestGateTest {
    @Test void recentRequestSuppressesDuplicatesThroughTheStrictTwoMinuteBoundary() {
        var gate = new PeerHeaderRequestGate();
        long first = gate.acquire(1_000_123);
        assertTrue(first > 0);
        assertEquals(0, gate.acquire(1_000_124));
        assertEquals(0, gate.acquire(121_000_123));
        assertTrue(gate.acquire(121_000_124) > first);
    }
    @Test void responseAllowsImmediateNextRequestButOldFailureCannotCancelItsReservation() {
        var gate = new PeerHeaderRequestGate();
        long old = gate.acquire(100);
        gate.responded();
        long current = gate.acquire(100);
        assertTrue(current > old);
        gate.failed(old);
        assertEquals(0, gate.acquire(101));
        gate.failed(current);
        assertTrue(gate.acquire(101) > current);
    }
    @Test void wallClockRollbackDoesNotCreateAnImmediateRetransmit() {
        var gate = new PeerHeaderRequestGate(); gate.acquire(1000);
        assertEquals(0, gate.acquire(0));
    }
    @Test void concurrentProducersReserveOnlyOneWireRequest() throws Exception {
        var gate = new PeerHeaderRequestGate();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var attempts = new ArrayList<Future<Long>>();
            for (int i = 0; i < 32; i++) attempts.add(executor.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS)); return gate.acquire(1000);
            }));
            start.countDown(); int admitted = 0;
            for (var attempt : attempts) if (attempt.get(3, TimeUnit.SECONDS) != 0) admitted++;
            assertEquals(1, admitted);
        }
    }
}

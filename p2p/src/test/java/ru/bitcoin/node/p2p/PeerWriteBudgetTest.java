package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PeerWriteBudgetTest {
    @Test
    void enforcesPerPeerAndGlobalBytesAndReleasesExactlyOnce() throws Exception {
        var budget = new PeerWriteBudget(10, 10, 556, 1112);
        var first = budget.newAccount();
        var second = budget.newAccount();
        var third = budget.newAccount();
        var one = first.reserve(100);
        assertThrows(IOException.class, () -> first.reserve(0));
        var two = second.reserve(100);
        try {
            assertThrows(IOException.class, () -> first.reserve(0));
            assertThrows(IOException.class, () -> third.reserve(0));
            one.close();
            one.close();
            try (var replacement = third.reserve(100)) {
                assertThrows(IOException.class, () -> first.reserve(0));
            }
        } finally {
            one.close();
            two.close();
        }
        assertThrows(IOException.class, () -> first.reserve(Integer.MAX_VALUE));
        try (var restored = first.reserve(100); var other = second.reserve(100)) {
            assertThrows(IOException.class, () -> third.reserve(0));
        }
    }

    @Test
    void tinyMessagesCannotBypassPendingWriteCountLimits() throws Exception {
        var budget = new PeerWriteBudget(1, 2, 10000, 10000);
        var first = budget.newAccount();
        var second = budget.newAccount();
        try (var one = first.reserve(0)) {
            assertThrows(IOException.class, () -> first.reserve(0));
            try (var two = second.reserve(0)) {
                assertThrows(IOException.class, () -> budget.newAccount().reserve(0));
            }
        }
        try (var one = first.reserve(0); var two = second.reserve(0)) {
            assertNotNull(one);
            assertNotNull(two);
        }
    }

    @Test
    void simultaneousPeersCannotOverbookGlobalBudget() throws Exception {
        var budget = new PeerWriteBudget(1, 4, 10000, 10000);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var attempts = new ArrayList<Future<PeerWriteBudget.Reservation>>();
            var admitted = new ArrayList<PeerWriteBudget.Reservation>();
            try {
                for (int i = 0; i < 16; i++) {
                    attempts.add(executor.submit(() -> {
                        start.await();
                        try { return budget.newAccount().reserve(0); }
                        catch (IOException exhausted) { return null; }
                    }));
                }
                start.countDown();
                for (var attempt : attempts) {
                    var reservation = attempt.get(2, TimeUnit.SECONDS);
                    if (reservation != null) admitted.add(reservation);
                }
                assertEquals(4, admitted.size());
            } finally {
                admitted.forEach(PeerWriteBudget.Reservation::close);
            }
        }
        try (var restored = budget.newAccount().reserve(0)) {
            assertNotNull(restored);
        }
    }
}

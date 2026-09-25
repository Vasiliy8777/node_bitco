package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PeerDiscouragementManagerTest {

    @Test
    void discouragementExpires() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(1_700_000_000L));
        PeerDiscouragementManager manager = new PeerDiscouragementManager(
                Duration.ofMinutes(10), 100, now::get);
        InetAddress address = InetAddress.getByName("203.0.113.7");

        manager.discourage(address);
        assertTrue(manager.isDiscouraged(address));

        now.set(now.get().plusSeconds(599));
        assertTrue(manager.isDiscouraged(address));
        now.set(now.get().plusSeconds(1));
        assertFalse(manager.isDiscouraged(address));
        assertEquals(0, manager.size());
    }

    @Test
    void repeatedViolationRefreshesExpiry() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(1_700_000_000L));
        PeerDiscouragementManager manager = new PeerDiscouragementManager(
                Duration.ofMinutes(10), 100, now::get);
        InetAddress address = InetAddress.getByName("203.0.113.8");

        manager.discourage(address);
        now.set(now.get().plusSeconds(500));
        manager.discourage(address);
        now.set(now.get().plusSeconds(500));
        assertTrue(manager.isDiscouraged(address));
    }

    @Test
    void setIsBoundedAndEvictsOldestEntry() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(1_700_000_000L));
        PeerDiscouragementManager manager = new PeerDiscouragementManager(
                Duration.ofHours(1), 2, now::get);
        InetAddress first = InetAddress.getByName("203.0.113.1");
        InetAddress second = InetAddress.getByName("203.0.113.2");
        InetAddress third = InetAddress.getByName("203.0.113.3");

        manager.discourage(first);
        manager.discourage(second);
        manager.discourage(third);

        assertEquals(2, manager.size());
        assertFalse(manager.isDiscouraged(first));
        assertTrue(manager.isDiscouraged(second));
        assertTrue(manager.isDiscouraged(third));
    }
}

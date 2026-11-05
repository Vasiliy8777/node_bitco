package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class GetAddrResponseCacheTest {
    private final AtomicLong clock = new AtomicLong();
    private final GetAddrResponseCache cache = new GetAddrResponseCache(clock::get, () -> 17);
    private final InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 8333);

    @Test void ordinaryConnectionsShareSnapshotUntilStrictExpiry() throws Exception {
        var calls = new AtomicInteger();
        var known = known("8.8.8.8");
        Supplier<List<KnownPeerAddress>> fresh = () -> { calls.incrementAndGet(); return List.of(known); };
        var first = cache.select(4, bind, false, fresh);
        known.seen(new PeerAddress(InetAddress.getByName("8.8.8.8"), 8333, 9), Instant.ofEpochSecond(2000));
        clock.set(GetAddrResponseCache.MIN_LIFETIME + 17);
        var second = cache.select(4, bind, false, fresh);
        assertEquals(1, calls.get());
        assertEquals(first.getFirst().lastSeen(), second.getFirst().lastSeen());
        assertEquals(1, second.getFirst().peerAddress().services());
        clock.incrementAndGet();
        var refreshed = cache.select(4, bind, false, fresh);
        assertEquals(2, calls.get());
        assertEquals(9, refreshed.getFirst().peerAddress().services());
        assertEquals(Instant.ofEpochSecond(2000), refreshed.getFirst().lastSeen());
    }

    @Test void addrPermissionBypassesWithoutReplacingOrdinaryCache() throws Exception {
        var old = known("8.8.8.8");
        var newer = known("1.1.1.1");
        cache.select(4, bind, false, () -> List.of(old));
        assertEquals(newer.peerAddress(), cache.select(4, bind, true, () -> List.of(newer)).getFirst().peerAddress());
        assertEquals(old.peerAddress(), cache.select(4, bind, false, () -> List.of(newer)).getFirst().peerAddress());
    }

    @Test void networkAndBindAddressAndPortHaveSeparateCaches() {
        var calls = new AtomicInteger();
        Supplier<List<KnownPeerAddress>> fresh = () -> { calls.incrementAndGet(); return List.of(); };
        cache.select(4, bind, false, fresh);
        cache.select(16, bind, false, fresh);
        cache.select(4, new InetSocketAddress("127.0.0.2", 8333), false, fresh);
        cache.select(4, new InetSocketAddress("127.0.0.1", 18333), false, fresh);
        cache.select(4, bind, false, fresh);
        assertEquals(4, calls.get(), "Empty responses are cached too");
    }

    @Test void callerCannotMutateCachedTimestamps() throws Exception {
        var first = cache.select(4, bind, false, () -> List.of(knownUnchecked()));
        first.getFirst().seen(first.getFirst().peerAddress(), Instant.ofEpochSecond(9999));
        var second = cache.select(4, bind, false, () -> fail("Unexpected refresh"));
        assertEquals(Instant.ofEpochSecond(1000), second.getFirst().lastSeen());
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test void expiryWorksAcrossMonotonicClockOverflow() {
        clock.set(Long.MAX_VALUE - 10);
        var calls = new AtomicInteger();
        Supplier<List<KnownPeerAddress>> fresh = () -> { calls.incrementAndGet(); return List.of(); };
        cache.select(4, bind, false, fresh);
        clock.addAndGet(GetAddrResponseCache.MIN_LIFETIME + 18);
        cache.select(4, bind, false, fresh);
        assertEquals(2, calls.get());
    }

    private static KnownPeerAddress knownUnchecked() {
        try { return known("8.8.8.8"); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static KnownPeerAddress known(String ip) throws Exception {
        var address = new PeerAddress(InetAddress.getByName(ip), 8333, 1);
        return new KnownPeerAddress(address, PeerAddressSource.of(InetAddress.getLoopbackAddress()), Instant.ofEpochSecond(1000));
    }
}

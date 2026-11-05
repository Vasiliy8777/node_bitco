package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerPermission;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Core's GETADDR privacy cache, shared by ingress network and local bind endpoint. */
final class GetAddrResponseCache {
    static final long MIN_LIFETIME = Duration.ofHours(21).toNanos();
    static final long LIFETIME_SPREAD = Duration.ofHours(6).toNanos();
    private record Key(int remoteAddressBytes, InetSocketAddress bind) {}
    private record Entry(long expires, List<KnownPeerAddress> addresses) {}
    private final Map<Key, Entry> entries = new HashMap<>();
    private final LongSupplier clock;
    private final LongSupplier jitter;

    GetAddrResponseCache(LongSupplier clock) {
        this(clock, () -> ThreadLocalRandom.current().nextLong(LIFETIME_SPREAD));
    }

    GetAddrResponseCache(LongSupplier clock, LongSupplier jitter) {
        this.clock = Objects.requireNonNull(clock);
        this.jitter = Objects.requireNonNull(jitter);
    }

    List<KnownPeerAddress> select(Peer peer, Supplier<List<KnownPeerAddress>> fresh) {
        return select(peer.remoteAddress().getAddress().getAddress().length, peer.localAddress(),
                peer.hasPermission(PeerPermission.ADDR), fresh);
    }

    synchronized List<KnownPeerAddress> select(int remoteAddressBytes, InetSocketAddress bind,
                                               boolean bypass, Supplier<List<KnownPeerAddress>> fresh) {
        if (bypass) return snapshot(fresh.get());
        var key = new Key(remoteAddressBytes, Objects.requireNonNull(bind));
        long now = clock.getAsLong();
        var entry = entries.get(key);
        if (entry == null || now - entry.expires() > 0) {
            long extra = jitter.getAsLong();
            if (extra < 0 || extra >= LIFETIME_SPREAD) throw new IllegalArgumentException("GETADDR jitter out of range");
            entry = new Entry(now + MIN_LIFETIME + extra, snapshot(fresh.get()));
            entries.put(key, entry);
        }
        return snapshot(entry.addresses());
    }

    // AddrMan records mutate in place. Preserve the advertised address, services and timestamp,
    // and don't expose cached mutable records to callers.
    private static List<KnownPeerAddress> snapshot(List<KnownPeerAddress> addresses) {
        return addresses.stream().map(known -> {
            synchronized (known) {
                return new KnownPeerAddress(known.peerAddress(), known.source(), known.lastSeen());
            }
        }).toList();
    }
}

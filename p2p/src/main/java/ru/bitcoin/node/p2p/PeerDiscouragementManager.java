package ru.bitcoin.node.p2p;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded, expiring automatic peer discouragement set.
 *
 * This is deliberately not a permanent ban list. A protocol-violating peer is
 * refused for a limited period, then becomes eligible again. The set is bounded
 * so arbitrary remote addresses cannot create unbounded local memory usage.
 */
public final class PeerDiscouragementManager {
    public static final Duration DEFAULT_DURATION = Duration.ofHours(24);
    public static final int DEFAULT_MAX_ENTRIES = 50_000;

    private final Duration duration;
    private final int maxEntries;
    private final Supplier<Instant> clock;
    private final LinkedHashMap<String, Instant> discouraged = new LinkedHashMap<>();

    public PeerDiscouragementManager() {
        this(DEFAULT_DURATION, DEFAULT_MAX_ENTRIES, Instant::now);
    }

    public PeerDiscouragementManager(Duration duration, int maxEntries, Supplier<Instant> clock) {
        this.duration = Objects.requireNonNull(duration, "duration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("duration must be positive");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    public synchronized void discourage(InetAddress address) {
        Objects.requireNonNull(address, "address");
        discourageKey(address.getHostAddress(), clock.get());
    }

    public synchronized boolean isDiscouraged(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return isDiscouragedKey(address.getHostAddress(), clock.get());
    }

    public synchronized int size() {
        purgeExpired(clock.get());
        return discouraged.size();
    }

    private void discourageKey(String key, Instant now) {
        purgeExpired(now);
        discouraged.remove(key);
        discouraged.put(key, now.plus(duration));
        while (discouraged.size() > maxEntries) {
            Iterator<Map.Entry<String, Instant>> iterator = discouraged.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private boolean isDiscouragedKey(String key, Instant now) {
        Instant until = discouraged.get(key);
        if (until == null) return false;
        if (!until.isAfter(now)) {
            discouraged.remove(key);
            return false;
        }
        return true;
    }

    private void purgeExpired(Instant now) {
        discouraged.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}

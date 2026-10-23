package ru.bitcoin.node.rvn.p2p;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Exponential reconnect policy with bounded memory and no wall-clock dependence.
 */
public final class RvnPeerBackoff {
    private final long baseNanos, maxNanos;
    private final Map<RvnPeerEndpoint, State> states = new HashMap<>();

    private record State(int failures, long retryAt) {
    }

    public RvnPeerBackoff(long baseMillis, long maxMillis) {
        if (baseMillis < 1 || maxMillis < baseMillis || maxMillis > 3600000)
            throw new IllegalArgumentException("Invalid backoff bounds");
        baseNanos = TimeUnit.MILLISECONDS.toNanos(baseMillis);
        maxNanos = TimeUnit.MILLISECONDS.toNanos(maxMillis);
    }

    public synchronized boolean ready(RvnPeerEndpoint endpoint, long nowNanos) {
        State s = states.get(Objects.requireNonNull(endpoint));
        return s == null || nowNanos - s.retryAt >= 0;
    }

    public synchronized long remainingMillis(RvnPeerEndpoint endpoint, long nowNanos) {
        State s = states.get(Objects.requireNonNull(endpoint));
        if (s == null || nowNanos - s.retryAt >= 0) return 0;
        long nanos = s.retryAt - nowNanos;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    public synchronized void success(RvnPeerEndpoint endpoint) {
        states.remove(Objects.requireNonNull(endpoint));
    }

    public synchronized void failure(RvnPeerEndpoint endpoint, long nowNanos) {
        Objects.requireNonNull(endpoint);
        State previous = states.get(endpoint);
        int failures = previous == null ? 1 : Math.min(63, previous.failures + 1);
        long delay = baseNanos;
        for (int i = 1; i < failures && delay < maxNanos; i++)
            delay = delay > maxNanos / 2 ? maxNanos : Math.min(maxNanos, delay * 2);
        states.put(endpoint, new State(failures, nowNanos + delay));
    }

    public synchronized int trackedPeers() {
        return states.size();
    }
}

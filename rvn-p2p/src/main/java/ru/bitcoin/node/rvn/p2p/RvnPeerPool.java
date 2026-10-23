package ru.bitcoin.node.rvn.p2p;

import java.util.*;

/**
 * Bounded candidate pool; no socket connections are made here.
 */
public final class RvnPeerPool {
    private final int limit;
    private final LinkedHashSet<RvnPeerEndpoint> peers = new LinkedHashSet<>();

    public RvnPeerPool(int limit) {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("limit");
        this.limit = limit;
    }

    public synchronized boolean add(RvnPeerEndpoint peer) {
        Objects.requireNonNull(peer);
        if (peers.contains(peer) || peers.size() >= limit) return false;
        return peers.add(peer);
    }

    public synchronized boolean remove(RvnPeerEndpoint peer) {
        return peers.remove(peer);
    }

    public synchronized List<RvnPeerEndpoint> snapshot() {
        return List.copyOf(peers);
    }

    public synchronized int size() {
        return peers.size();
    }
}

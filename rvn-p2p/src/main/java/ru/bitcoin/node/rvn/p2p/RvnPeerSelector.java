package ru.bitcoin.node.rvn.p2p;
import java.util.*;
/** Round-robin selection from a bounded peer registry, respecting backoff. */
public final class RvnPeerSelector {
    private final RvnPeerPool pool;
    private final RvnPeerBackoff backoff;
    private int cursor;
    public RvnPeerSelector(RvnPeerPool pool, RvnPeerBackoff backoff) {
        this.pool = Objects.requireNonNull(pool); this.backoff = Objects.requireNonNull(backoff);
    }
    public synchronized Optional<RvnPeerEndpoint> next(long nowNanos) {
        List<RvnPeerEndpoint> peers = pool.snapshot();
        if (peers.isEmpty()) return Optional.empty();
        for (int i=0;i<peers.size();i++) {
            int index = Math.floorMod(cursor + i, peers.size());
            RvnPeerEndpoint peer = peers.get(index);
            if (backoff.ready(peer, nowNanos)) {
                cursor = (index + 1) % peers.size();
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }
    public void failure(RvnPeerEndpoint peer, long nowNanos) { backoff.failure(peer, nowNanos); }
    public void success(RvnPeerEndpoint peer) { backoff.success(peer); }
}

package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.p2p.Peer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Core 31.1 FindNextBlocks window traversal, with per-peer validity and service gates. */
public final class BlockDownloadWindowStallDetector {
    public Optional<Peer> findStallingPeer(List<BlockIndex> path, int next, int window,
            Function<BlockIndex, Boolean> available, Function<BlockIndex, Optional<Peer>> owner) {
        return findStallingPeer(path, next, window, available, owner, null, ignored -> true, ignored -> true);
    }

    public Optional<Peer> findStallingPeer(List<BlockIndex> path, int next, int window,
            Function<BlockIndex, Boolean> available, Function<BlockIndex, Optional<Peer>> owner,
            Peer observer, Predicate<BlockIndex> traversable, Predicate<BlockIndex> requestable) {
        Objects.requireNonNull(path, "connectPath");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(owner, "inFlightPeer");
        Objects.requireNonNull(traversable, "traversable");
        Objects.requireNonNull(requestable, "requestable");
        if (next < 0 || next > path.size()) throw new IllegalArgumentException("nextToProcess is outside connectPath");
        if (window <= 0) throw new IllegalArgumentException("downloadWindow must be positive");
        // A long sum avoids overflow for a valid window that extends past the list.
        long end = (long) next + window;
        Peer waitingFor = null;
        for (int position = next; position < path.size() && position <= end; position++) {
            BlockIndex block = path.get(position);
            // Core checks tree validity and witness capability before HAVE_DATA/in-flight.
            if (!traversable.test(block)) return Optional.empty();
            if (Boolean.TRUE.equals(available.apply(block))) continue;
            Optional<Peer> inFlight = Objects.requireNonNull(owner.apply(block), "inFlightPeer returned null");
            if (inFlight.isPresent()) {
                if (waitingFor == null) waitingFor = inFlight.get();
                continue;
            }
            // Core tests window+1 before the limited-history retention check.
            if (position == end) return waitingFor == observer ? Optional.empty() : Optional.ofNullable(waitingFor);
            // A missing block that this peer can fetch is useful work, not a window stall.
            // An old block outside a limited peer's retention range is skipped instead.
            if (requestable.test(block)) return Optional.empty();
        }
        return Optional.empty();
    }
}

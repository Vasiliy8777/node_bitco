package ru.bitcoin.node.rvn.app.chain;

import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.storage.*;
import java.io.IOException;
import java.util.*;

/** Branch-specific assets header-version activation. Raven's Condition checks bit 6
 * alone (unlike Bitcoin's additional top-bit mask). Parameters and transitions
 * match pinned Core chainparams.cpp / versionbits.cpp, not a canonical-chain height.
 */
public final class RvnAssetsDeployment {
    enum State { DEFINED, STARTED, LOCKED_IN, ACTIVE, FAILED }
    private final int period, threshold;
    private final long start, timeout;
    private final Map<RvnHash256, State> cache = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<RvnHash256, State> entry) { return size() > 512; }
    };

    public RvnAssetsDeployment() { this(2016, 1814, 1540944000L, 1572480000L); }
    RvnAssetsDeployment(int period, int threshold, long start, long timeout) {
        if (period < 1 || threshold < 1 || threshold > period || start < 0 || timeout <= start)
            throw new IllegalArgumentException("Deployment parameters");
        this.period = period; this.threshold = threshold; this.start = start; this.timeout = timeout;
    }

    public boolean activeForNext(RvnStoredHeader parent, RvnHeaderLookup branch) throws IOException {
        return stateForNext(parent, branch) == State.ACTIVE;
    }

    synchronized State stateForNext(RvnStoredHeader parent, RvnHeaderLookup branch) throws IOException {
        long endHeight = parent.height() - (parent.height() + 1) % period;
        if (endHeight < 0) return State.DEFINED;
        var cursor = branch.ancestor(parent, endHeight);
        List<RvnStoredHeader> pending = new ArrayList<>();
        State state;
        while (true) {
            state = cache.get(cursor.id());
            if (state != null) break;
            if (median(cursor, branch) < start) {
                state = State.DEFINED; cache.put(cursor.id(), state); break;
            }
            pending.add(cursor);
            if (cursor.height() < period) { state = State.DEFINED; break; }
            cursor = branch.ancestor(cursor, cursor.height() - period);
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            var end = pending.get(i);
            if (state == State.DEFINED) {
                long time = median(end, branch);
                if (time >= timeout) state = State.FAILED;
                else if (time >= start) state = State.STARTED;
            } else if (state == State.STARTED) {
                if (median(end, branch) >= timeout) state = State.FAILED;
                else {
                    int signals = 0;
                    var block = end;
                    for (int j = 0; j < period; j++) {
                        if ((block.version() & (1 << 6)) != 0) signals++;
                        if (j + 1 < period) block = branch.require(block.parentId());
                    }
                    if (signals >= threshold) state = State.LOCKED_IN;
                }
            } else if (state == State.LOCKED_IN) state = State.ACTIVE;
            cache.put(end.id(), state);
        }
        return state;
    }

    private static long median(RvnStoredHeader end, RvnHeaderLookup branch) throws IOException {
        long[] times = new long[11]; int size = 0;
        var cursor = end;
        while (size < times.length) {
            times[size++] = cursor.timestamp();
            if (cursor.height() == 0) break;
            cursor = branch.require(cursor.parentId());
        }
        Arrays.sort(times, 0, size);
        return times[size / 2];
    }
}

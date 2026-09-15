package ru.bitcoin.node.mempool;

/** Core-style rolling fee floor, measured against this node's virtual-byte budget. */
final class RollingMinimumFee {
    private double rate;
    private long updated;
    private boolean blockSinceBump;
    void bump(long removedRate, long incremental, long now) {
        long next = Math.addExact(removedRate, incremental);
        if (next > rate) { rate = next; blockSinceBump = false; updated = now; }
    }
    void blockConnected(long now) { blockSinceBump = true; updated = now; }
    long get(long now, long usage, long maximum, long incremental) {
        if (!blockSinceBump || rate == 0) return Math.round(rate);
        if (now > updated + 10) {
            double halfLife = 12 * 3600;
            if (usage < maximum / 4) halfLife /= 4;
            else if (usage < maximum / 2) halfLife /= 2;
            rate /= Math.pow(2, (now - updated) / halfLife);
            updated = now;
            if (rate < incremental / 2.0) { rate = 0; return 0; }
        }
        return Math.max(Math.round(rate), incremental);
    }
}

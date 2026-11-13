package ru.bitcoin.node.mempool;

/** Core rolling fee floor, with decay selected by dynamic memory pressure. */
final class RollingMinimumFee {
    private double rate;
    private long updated;
    private boolean blockSinceBump;
    void restoreFrom(RollingMinimumFee source) {
        rate = source.rate;
        updated = source.updated;
        blockSinceBump = source.blockSinceBump;
    }
    void bump(long removedRate, long incremental, long now) {
        long next = Math.addExact(removedRate, incremental);
        if (next > rate) { rate = next; blockSinceBump = false; updated = now; }
    }
    void blockConnected(long now) { blockSinceBump = true; updated = now; }
    long get(long now, long usage, long maximum, long incremental) {
        return get(now, () -> usage, maximum, incremental);
    }
    long get(long now, java.util.function.LongSupplier memoryUsage, long maximum, long incremental) {
        if (!blockSinceBump || rate == 0) return Math.round(rate);
        if (now > updated + 10) {
            double halfLife = 12 * 3600;
            long usage = memoryUsage.getAsLong();
            if (usage < maximum / 4) halfLife /= 4;
            else if (usage < maximum / 2) halfLife /= 2;
            rate /= Math.pow(2, (now - updated) / halfLife);
            updated = now;
            if (rate < incremental / 2.0) { rate = 0; return 0; }
        }
        return Math.max(Math.round(rate), incremental);
    }
}

package ru.bitcoin.node.rvn.consensus;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters only; no verification decisions are based on metrics.
 */
public final class RvnHeaderAdmissionMetrics {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder batches = new LongAdder();

    public void accepted(int count) {
        if (count < 0) throw new IllegalArgumentException("count");
        batches.increment();
        accepted.add(count);
    }

    public void rejected() {
        batches.increment();
        rejected.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(accepted.sum(), rejected.sum(), batches.sum());
    }

    public record Snapshot(long acceptedHeaders, long rejectedBatches, long attemptedBatches) {
    }
}

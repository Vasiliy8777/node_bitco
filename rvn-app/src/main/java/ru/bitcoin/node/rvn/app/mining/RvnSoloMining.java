package ru.bitcoin.node.rvn.app.mining;

import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.RvnDifficultyScreen;
import ru.bitcoin.node.rvn.pow.RvnCudaKawpowWorker;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded local solo-mining rounds. Chainstate admission and relay are supplied
 * by the node backend; a header-only or subset-validated backend must not be
 * registered for production mining. No pool, RPC, or external miner protocol. */
public final class RvnSoloMining {
    public interface Backend {
        /** A fully checked template, or empty while synchronizing/offline. */
        Optional<Job> next() throws IOException;
        /** Checks parent, validation generation and deadline against current state. */
        boolean current(Job job) throws IOException;
        /** Atomically rechecks freshness, fully validates and durably connects.
         * Returns empty for stale work. It must never relay before durable commit. */
        Optional<Accepted> accept(Job job, RvnBlock block) throws IOException;
        /** Announces a durably accepted block. Failures must be retried by the backend. */
        void relay(Accepted block) throws IOException;
        default String waitingReason() throws IOException { return "no validated mining job available"; }
    }
    @FunctionalInterface public interface Search {
        RvnCudaKawpowWorker.Batch search(long height, byte[] headerHash, long nonce, int count, BigInteger target) throws IOException;
    }
    public record Accepted(RvnBlock block) { public Accepted { Objects.requireNonNull(block); } }
    public record Job(long generation, RvnBlock template, BigInteger powLimit, long expiresAtSeconds) {
        public Job {
            Objects.requireNonNull(template); Objects.requireNonNull(powLimit);
            if (!template.header().isKawpow() || generation < 0 || expiresAtSeconds <= template.header().timestamp())
                throw new IllegalArgumentException("Invalid RVN mining job");
            RvnDifficultyScreen.requireWithinLimit(template.header().compactBits(), powLimit);
            RvnBlockBodyChecks.requireValid(template, true);
            RvnCoinbase.requireHeight(template.transactions().getFirst(), template.header().declaredHeight());
        }
        BigInteger target() { return RvnDifficultyScreen.requireWithinLimit(template.header().compactBits(), powLimit); }
    }
    public enum State { WAITING, STALE, SEARCHED, ACCEPTED }
    public synchronized String waitingReason() throws IOException { return backend.waitingReason(); }
    public record Progress(State state, int hashes, int accepted) {}
    private final Backend backend;
    private final Search search;
    private final LongSupplier clock;
    private final int batchSize;
    private Job job;
    private long nonce;
    private boolean exhausted;
    private Accepted pendingRelay;

    public RvnSoloMining(Backend backend, Search search, LongSupplier clock, int batchSize) {
        this.backend = Objects.requireNonNull(backend); this.search = Objects.requireNonNull(search);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1 || batchSize > 65536) throw new IllegalArgumentException("RVN mining batch size");
        this.batchSize = batchSize;
    }

    /** One short GPU batch. The owner can stop between rounds; interrupting a
     * CUDA search closes its subprocess. Unsigned nonce exhaustion requests a
     * new coinbase extraNonce instead of silently searching duplicate work. */
    public synchronized Progress advance() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RVN miner interrupted");
        if (pendingRelay != null) {
            backend.relay(pendingRelay);
            pendingRelay = null;
            return new Progress(State.ACCEPTED, 0, 1);
        }
        if (job == null || !fresh(job)) {
            job = null;
            var available = backend.next();
            if (available.isEmpty()) return new Progress(State.WAITING, 0, 0);
            var next = available.get();
            if (!fresh(next)) return new Progress(State.STALE, 0, 0);
            job = next; nonce = 0; exhausted = false;
        }
        if (exhausted) {
            // Backend must advance its generation/extraNonce after exhaustion.
            var next = backend.next();
            if (next.isEmpty()) return new Progress(State.WAITING, 0, 0);
            if (Arrays.equals(next.get().template().header().kawpowInputDigest(), job.template().header().kawpowInputDigest()))
                throw new IOException("RVN backend reused an exhausted mining template");
            job = next.get(); nonce = 0; exhausted = false;
            if (!fresh(job)) { job = null; return new Progress(State.STALE, 0, 0); }
        }
        int count = batchSize;
        if (nonce < 0 && Long.compareUnsigned(-nonce, count) < 0) count = (int) -nonce;
        // Ethash accepts Core's display-order header hash; RVN serializes mix
        // in uint256 wire order. Keep both conversions at this boundary.
        byte[] headerHash = reverse(job.template().header().kawpowInputDigest());
        var result = search.search(job.template().header().declaredHeight(), headerHash, nonce, count, job.target());
        int tested = count;
        while (result.overflow()) {
            if (result.tested() != count || count == 1) throw new IOException("Invalid RVN worker overflow response");
            if (!fresh(job)) { job = null; return new Progress(State.STALE, tested, 0); }
            count = Math.max(1, count / 2);
            result = search.search(job.template().header().declaredHeight(), headerHash, nonce, count, job.target());
            tested += count;
        }
        if (result.tested() != count) throw new IOException("RVN worker batch count mismatch");
        var seen = new HashSet<Long>();
        for (var candidate : result.candidates()) {
            if (Long.compareUnsigned(candidate.nonce() - nonce, count) >= 0 || !seen.add(candidate.nonce()))
                throw new IOException("RVN worker returned duplicate/out-of-range candidate");
        }
        long previous = nonce; nonce += count;
        exhausted = Long.compareUnsigned(nonce, previous) < 0;
        if (!fresh(job)) { job = null; return new Progress(State.STALE, tested, 0); }
        for (var candidate : result.candidates()) {
            if (!fresh(job)) { job = null; return new Progress(State.STALE, tested, 0); }
            var block = RvnBlockTemplateBuilder.withProof(job.template(), candidate.nonce(), reverse(candidate.mix()));
            var accepted = backend.accept(job, block);
            if (accepted.isPresent()) {
                job = null;
                pendingRelay = accepted.get();
                backend.relay(pendingRelay);
                pendingRelay = null;
                return new Progress(State.ACCEPTED, tested, 1);
            }
        }
        return new Progress(State.SEARCHED, tested, 0);
    }
    private static byte[] reverse(byte[] bytes) {
        byte[] result = bytes.clone();
        for (int i = 0; i < result.length / 2; i++) {
            byte value = result[i]; result[i] = result[result.length - 1 - i]; result[result.length - 1 - i] = value;
        }
        return result;
    }
    private boolean fresh(Job value) throws IOException {
        return clock.getAsLong() < value.expiresAtSeconds() && backend.current(value);
    }
}

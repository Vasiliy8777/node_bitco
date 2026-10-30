package ru.bitcoin.node.rvn.app.mining;

import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.RvnDifficultyScreen;
import ru.bitcoin.node.rvn.pow.RvnCudaKawpowWorker;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.LongSupplier;
import ru.bitcoin.node.common.NoncePermutation;

/** Bounded local solo-mining rounds. Chainstate admission and relay are supplied
 * by the node backend; a header-only or subset-validated backend must not be
 * registered for production mining. No pool, RPC, or external miner protocol. */
public final class RvnSoloMining {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RvnSoloMining.class);
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
        default boolean diagnostic() { return false; }
        default String waitingReason() throws IOException { return "no validated mining job available"; }
    }
    @FunctionalInterface public interface Search {
        RvnCudaKawpowWorker.Batch search(long height, byte[] headerHash, long nonce, int count, BigInteger target) throws IOException;
        default long counter(byte[] headerHash, long actualNonce) { return actualNonce; }
    }
    public static Search permutedSearch(RvnCudaKawpowWorker worker) {
        return new Search() {
            public RvnCudaKawpowWorker.Batch search(long height, byte[] header, long counter, int count, BigInteger target) throws IOException {
                return worker.searchPermuted(height, header, counter, count, target);
            }
            public long counter(byte[] header, long actualNonce) {
                return NoncePermutation.inverse64(actualNonce, NoncePermutation.key64(header));
            }
        };
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
    /** Session counters survive an advance() failure, so relay errors cannot hide a found block. */
    public record Statistics(long targetCandidates, long staleCandidates, long locallyAcceptedBlocks,
                             long relayedBlocks, long admissionFailures, long relayFailures) {}
    private long targetCandidates, staleCandidates, locallyAcceptedBlocks, relayedBlocks, admissionFailures, relayFailures;
    public synchronized Statistics statistics() {
        return new Statistics(targetCandidates, staleCandidates, locallyAcceptedBlocks, relayedBlocks, admissionFailures, relayFailures);
    }
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
            relayPending();
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
            if (Long.compareUnsigned(search.counter(headerHash, candidate.nonce()) - nonce, count) >= 0 || !seen.add(candidate.nonce()))
                throw new IOException("RVN worker returned duplicate/out-of-range candidate");
        }
        targetCandidates += result.candidates().size();
        if (!backend.diagnostic() && !result.candidates().isEmpty())
            log.info("RVN target candidates: cpuVerified={}, height={}, generation={}, nBits={}",
                    result.candidates().size(), job.template().header().declaredHeight(), job.generation(),
                    Long.toHexString(job.template().header().compactBits()));
        long previous = nonce; nonce += count;
        exhausted = Long.compareUnsigned(nonce, previous) < 0;
        if (!fresh(job)) {
            staleCandidates += result.candidates().size();
            job = null; return new Progress(State.STALE, tested, 0);
        }
        int remainingCandidates = result.candidates().size();
        for (var candidate : result.candidates()) {
            if (!fresh(job)) {
                staleCandidates += remainingCandidates;
                job = null; return new Progress(State.STALE, tested, 0);
            }
            remainingCandidates--;
            var block = RvnBlockTemplateBuilder.withProof(job.template(), candidate.nonce(), reverse(candidate.mix()));
            Optional<Accepted> accepted;
            try { accepted = backend.accept(job, block); }
            catch (IOException | RuntimeException e) { admissionFailures++; throw e; }
            if (accepted.isPresent()) {
                job = null;
                locallyAcceptedBlocks++;
                staleCandidates += remainingCandidates; // Other candidates share the now-consumed parent.
                pendingRelay = accepted.get();
                if (!backend.diagnostic()) log.info("RVN mined block: locallyAcceptedBlocksSession={}, height={}; relay pending",
                        locallyAcceptedBlocks, block.header().declaredHeight());
                relayPending();
                return new Progress(State.ACCEPTED, tested, 1);
            }
            staleCandidates++; // Atomic admission refused a job that lost freshness.
        }
        return new Progress(State.SEARCHED, tested, 0);
    }
    private void relayPending() throws IOException {
        try { backend.relay(pendingRelay); }
        catch (IOException | RuntimeException e) { relayFailures++; throw e; }
        pendingRelay = null; relayedBlocks++;
        if (!backend.diagnostic()) log.info("RVN mined block relay completed: relayedBlocksSession={}; peer possession confirmed by production backend",
                relayedBlocks);
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

package ru.bitcoin.node.rvn.app.mining;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.app.chain.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.storage.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic proof engine exercises jobs/admission/durability; real CUDA and CPU
 * known-answer validation lives in rvn-pow and is not replaced by this fixture. */
class RvnSoloMiningTest {
    @TempDir Path temp;
    static final long NOW = 1600000000L;
    static final RvnHash256 GENESIS = new RvnHash256(new byte[32]);
    static final RvnPayoutAddress PAYOUT = RvnPayoutAddress.parse("RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV", RvnNetwork.MAINNET);
    static final RvnKawpowMixEngine POW = new RvnKawpowMixEngine() {
        public RvnPowResult calculate(RvnPowAlgorithm a, byte[] raw) { return new RvnPowResult(RvnWire.doubleSha(raw), new byte[32]); }
        public RvnKawpowMixResult calculateKawpow(byte[] raw) {
            return new RvnKawpowMixResult(new byte[32], calculate(RvnPowAlgorithm.KAWPOW, raw));
        }
    };
    byte[] genesis() {
        byte[] raw = new byte[120]; RvnKawpowWords.writeLE((int) NOW - 60, raw, 68);
        RvnKawpowWords.writeLE(0x1e00ffff, raw, 72); return raw;
    }
    RvnHeaderStore headers() throws Exception {
        return new RvnHeaderStore(temp.resolve("headers"), GENESIS, genesis(), RvnMainnetDifficulty.POW_LIMIT);
    }
    RvnCoinStore coins() throws Exception { return new RvnCoinStore(temp.resolve("coins"), GENESIS); }
    RvnEmptyBlockMiningBackend backend(RvnHeaderStore h, RvnCoinStore c, AtomicBoolean ready, AtomicLong clock,
                                      RvnEmptyBlockMiningBackend.Relay relay) throws Exception {
        return new RvnEmptyBlockMiningBackend(h, c, new RvnMainnetHeaderChain(h, POW, clock::get),
                new RvnNativeBlockReplay(h, c), PAYOUT, ready::get, clock::get, relay);
    }
    static RvnCudaKawpowWorker.Batch hit(long nonce, int count) {
        return new RvnCudaKawpowWorker.Batch(List.of(new RvnCudaKawpowWorker.Candidate(nonce, new byte[32], new byte[32])), false, count);
    }
    @Test void permutedCandidatesAreCheckedByLogicalCounterAndInstalledAsActualNonce() throws Exception {
        for (boolean outside : new boolean[]{false, true}) {
            try (var h = headers(); var c = coins()) {
                var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, block) -> {});
                var actual = new AtomicLong();
                var search = new RvnSoloMining.Search() {
                    public RvnCudaKawpowWorker.Batch search(long height, byte[] hash, long counter, int count, java.math.BigInteger target) {
                        actual.set(ru.bitcoin.node.common.NoncePermutation.permute64(counter + (outside ? count : 0),
                                ru.bitcoin.node.common.NoncePermutation.key64(hash)));
                        return hit(actual.get(), count);
                    }
                    public long counter(byte[] hash, long nonce) {
                        return ru.bitcoin.node.common.NoncePermutation.inverse64(nonce,
                                ru.bitcoin.node.common.NoncePermutation.key64(hash));
                    }
                };
                var miner = new RvnSoloMining(backend, search, () -> NOW, 64);
                if (outside) assertThrows(IOException.class, miner::advance);
                else {
                    assertEquals(RvnSoloMining.State.ACCEPTED, miner.advance().state());
                    var block = RvnBlockCodec.parse(h.blockBody(c.tip().id()));
                    assertEquals(actual.get(), block.header().nonce64Bits());
                }
            }
        }
    }
    @Test void peerLossInvalidatesJobsAndExplainsWaiting() throws Exception {
        try (var h = headers(); var c = coins()) {
            var coverage = new AtomicBoolean(false); var peer = new AtomicBoolean(false);
            var clock = new AtomicLong(NOW);
            var backend = new RvnEmptyBlockMiningBackend(h, c, new RvnMainnetHeaderChain(h, POW, clock::get),
                    new RvnNativeBlockReplay(h, c), PAYOUT, coverage::get, peer::get, clock::get,
                    (id, block) -> fail("No relay expected"));
            assertTrue(backend.next().isEmpty());
            assertTrue(backend.waitingReason().contains("coverage"));
            coverage.set(true);
            assertTrue(backend.next().isEmpty());
            assertTrue(backend.waitingReason().contains("peer"));
            peer.set(true);
            var job = backend.next().orElseThrow();
            assertTrue(backend.current(job));
            peer.set(false);
            assertFalse(backend.current(job));
            assertTrue(backend.accept(job, job.template()).isEmpty());
            clock.addAndGet(721);
            assertTrue(backend.waitingReason().contains("timestamp"));
        }
    }
    @Test void equalFreshTipsCannotCertifyAnIncompleteHistoricalValidator() throws Exception {
        try (var h = headers(); var c = coins()) {
            var replay = new RvnNativeBlockReplay(h, c, true);
            var backend = new RvnEmptyBlockMiningBackend(h, c, new RvnMainnetHeaderChain(h, POW, () -> NOW),
                    replay, PAYOUT, replay::completeHistoricalValidation, () -> NOW, (id, block) -> fail("No relay"));
            assertEquals(h.best().id(), c.tip().id());
            assertFalse(replay.completeHistoricalValidation());
            assertTrue(backend.next().isEmpty());
        }
    }
    @Test void foundCandidateConnectsDurablyBeforeRelayAndCannotBeSubmittedTwice() throws Exception {
        RvnHash256 accepted;
        try (var h = headers(); var c = coins()) {
            var relayed = new AtomicInteger();
            var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, block) -> {
                assertEquals(1, c.tip().height()); assertEquals(1, h.best().height()); relayed.incrementAndGet();
            });
            var job = backend.next().orElseThrow();
            var wrongMix = new byte[32]; wrongMix[0] = 1;
            assertThrows(SecurityException.class, () -> backend.accept(job, RvnBlockTemplateBuilder.withProof(job.template(), 0, wrongMix)));
            assertEquals(0, c.tip().height()); assertEquals(0, h.best().height());
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> hit(nonce, count), () -> NOW, 64);
            assertEquals(RvnSoloMining.State.ACCEPTED, miner.advance().state());
            assertEquals(new RvnSoloMining.Statistics(1, 0, 1, 1, 0, 0), miner.statistics());
            assertEquals(1, relayed.get()); accepted = c.tip().id();
            assertTrue(backend.accept(job, job.template()).isEmpty());
        }
        try (var h = headers(); var c = coins()) {
            assertEquals(accepted, c.tip().id()); assertEquals(accepted, h.best().id());
            assertNotNull(h.blockBody(accepted));
        }
    }
    @Test void noWorkWithoutCertifiedStateAndStaleResultsNeverConnect() throws Exception {
        try (var h = headers(); var c = coins()) {
            var ready = new AtomicBoolean(false); var clock = new AtomicLong(NOW);
            var backend = backend(h, c, ready, clock, (id, b) -> fail("Must not relay stale work"));
            assertTrue(backend.next().isEmpty()); ready.set(true);
            var searches = new AtomicInteger();
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
                searches.incrementAndGet(); ready.set(false); return hit(nonce, count);
            }, clock::get, 64);
            assertEquals(RvnSoloMining.State.STALE, miner.advance().state());
            assertEquals(new RvnSoloMining.Statistics(1, 1, 0, 0, 0, 0), miner.statistics());
            assertEquals(1, searches.get()); assertEquals(0, c.tip().height());
            assertEquals(RvnSoloMining.State.WAITING, miner.advance().state());
            ready.set(true); var job = backend.next().orElseThrow(); clock.addAndGet(31);
            assertTrue(backend.current(job), "Unchanged parent must not expire after 30 seconds");
            clock.addAndGet(720);
            assertFalse(backend.current(job), "A stale chain tip must still disable mining");
            assertTrue(backend.accept(job, job.template()).isEmpty());
        }
    }
    @Test void failedRelayRetriesCommittedCandidateWithoutSearchingOrConnectingAgain() throws Exception {
        try (var h = headers(); var c = coins()) {
            var calls = new AtomicInteger(); var searches = new AtomicInteger();
            var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, block) -> {
                if (calls.incrementAndGet() == 1) throw new IOException("Peer disconnected");
            });
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
                searches.incrementAndGet(); return hit(nonce, count);
            }, () -> NOW, 64);
            assertThrows(IOException.class, miner::advance); assertEquals(1, c.tip().height());
            assertEquals(new RvnSoloMining.Statistics(1, 0, 1, 0, 0, 1), miner.statistics());
            assertEquals(Optional.of(c.tip()), c.pendingMinedBlock());
            assertEquals(RvnSoloMining.State.ACCEPTED, miner.advance().state());
            assertEquals(1, searches.get()); assertEquals(1, c.tip().height()); assertEquals(2, calls.get());
            assertEquals(new RvnSoloMining.Statistics(1, 0, 1, 1, 0, 1), miner.statistics());
            assertTrue(c.pendingMinedBlock().isEmpty());
        }
    }
    @Test void admissionFailureRetainsTargetHitBeforeProgressCanBeReturned() throws Exception {
        try (var h = headers(); var c = coins()) {
            var source = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, block) -> fail());
            var job = source.next().orElseThrow();
            var failing = new RvnSoloMining.Backend() {
                public Optional<RvnSoloMining.Job> next() { return Optional.of(job); }
                public boolean current(RvnSoloMining.Job value) { return true; }
                public Optional<RvnSoloMining.Accepted> accept(RvnSoloMining.Job value, RvnBlock block) {
                    throw new SecurityException("Synthetic admission failure");
                }
                public void relay(RvnSoloMining.Accepted block) { fail("Must not relay rejected block"); }
            };
            var miner = new RvnSoloMining(failing, (height, hash, nonce, count, target) -> hit(nonce, count), () -> NOW, 64);
            assertThrows(SecurityException.class, miner::advance);
            assertEquals(new RvnSoloMining.Statistics(1, 0, 0, 0, 1, 0), miner.statistics());
            assertEquals(0, c.tip().height());
        }
    }
    @Test void restartRecoversDurableCandidateBeforeStartingAnotherJob() throws Exception {
        try (var h = headers(); var c = coins()) {
            var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, b) -> fail("Not relayed yet"));
            var job = backend.next().orElseThrow();
            assertTrue(backend.accept(job, RvnBlockTemplateBuilder.withProof(job.template(), 0, new byte[32])).isPresent());
            assertEquals(Optional.of(c.tip()), c.pendingMinedBlock());
        }
        try (var h = headers(); var c = coins()) {
            var relayed = new AtomicInteger();
            var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, b) -> {
                assertEquals(1, c.tip().height()); assertTrue(c.pendingMinedBlock().isPresent()); relayed.incrementAndGet();
            });
            assertTrue(backend.next().isPresent()); assertEquals(1, relayed.get());
            assertTrue(c.pendingMinedBlock().isEmpty());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {64, 65536})
    void unchangedTipKeepsTemplateAndNonceAcrossFormerExpiry(int batchSize) throws Exception {
        try (var h = headers(); var c = coins()) {
            var clock = new AtomicLong(NOW); var starts = new ArrayList<Long>(); var hashes = new ArrayList<byte[]>();
            var backend = backend(h, c, new AtomicBoolean(true), clock, (id, b) -> fail());
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
                starts.add(nonce); hashes.add(hash); return new RvnCudaKawpowWorker.Batch(List.of(), false, count);
            }, clock::get, batchSize);
            miner.advance(); miner.advance(); clock.addAndGet(31); miner.advance();
            clock.addAndGet(299); miner.advance();
            assertEquals(List.of(0L, (long) batchSize, 2L * batchSize, 3L * batchSize), starts);
            for (var hash : hashes) assertArrayEquals(hashes.getFirst(), hash);
        }
    }
    @Test void tipChangeDuringGpuSearchDiscardsResultAndCreatesJobOnNewParent() throws Exception {
        try (var h = headers(); var c = coins()) {
            var ready = new AtomicBoolean(true); var clock = new AtomicLong(NOW);
            var starts = new ArrayList<Long>(); var heights = new ArrayList<Long>();
            var digests = new ArrayList<byte[]>(); var calls = new AtomicInteger();
            var backend = backend(h, c, ready, clock, (id, block) -> {});
            var competing = backend(h, c, ready, clock, (id, block) -> {});
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
                starts.add(nonce); heights.add(height); digests.add(hash);
                if (calls.getAndIncrement() == 0) {
                    var competingJob = competing.next().orElseThrow();
                    assertTrue(competing.accept(competingJob,
                            RvnBlockTemplateBuilder.withProof(competingJob.template(), 0, new byte[32])).isPresent());
                    // A valid candidate for the old parent must now be discarded.
                    return hit(nonce, count);
                }
                return new RvnCudaKawpowWorker.Batch(List.of(), false, count);
            }, clock::get, 65536);
            assertEquals(RvnSoloMining.State.STALE, miner.advance().state());
            assertEquals(1, c.tip().height());
            assertEquals(RvnSoloMining.State.SEARCHED, miner.advance().state());
            assertEquals(List.of(1L, 2L), heights);
            assertEquals(List.of(0L, 0L), starts);
            assertFalse(Arrays.equals(digests.get(0), digests.get(1)));
            assertEquals(1, c.tip().height(), "Old-parent GPU candidate must never connect");
        }
    }
    @Test void clockRollbackRefreshesOnlyWhenTemplateExceedsFutureTimeLimit() throws Exception {
        try (var h = headers(); var c = coins()) {
            var clock = new AtomicLong(NOW);
            var backend = backend(h, c, new AtomicBoolean(true), clock, (id, block) -> fail());
            var job = backend.next().orElseThrow();
            clock.addAndGet(-720);
            assertTrue(backend.current(job), "Future timestamp equality is still allowed");
            clock.decrementAndGet();
            assertFalse(backend.current(job), "Template timestamp is now too far in the future");
            assertTrue(backend.accept(job, job.template()).isEmpty());
            var replacement = backend.next().orElseThrow();
            assertTrue(backend.current(replacement));
            assertTrue(replacement.generation() > job.generation());
            assertArrayEquals(job.template().header().previousHashWire(), replacement.template().header().previousHashWire());
            assertNotEquals(job.template().header().timestamp(), replacement.template().header().timestamp());
        }
    }
    @Test void workerOverflowRetriesSmallerRangesWithoutSkippingNonces() throws Exception {
        try (var h = headers(); var c = coins()) {
            var starts = new ArrayList<Long>(); var counts = new ArrayList<Integer>();
            var backend = backend(h, c, new AtomicBoolean(true), new AtomicLong(NOW), (id, b) -> fail());
            var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
                starts.add(nonce); counts.add(count);
                return new RvnCudaKawpowWorker.Batch(List.of(), count > 128, count);
            }, () -> NOW, 256);
            assertEquals(384, miner.advance().hashes()); miner.advance();
            assertEquals(List.of(0L, 0L, 128L, 128L), starts);
            assertEquals(List.of(256, 128, 256, 128), counts);
        }
    }
    @Test void bridgeConvertsHeaderAndMixByteOrderBeforeAdmission() throws Exception {
        var template = RvnBlockTemplateBuilder.build(0x30000000, new byte[32], 1, NOW, 0x207fffff,
                1, PAYOUT, List.of(), 0, true);
        var job = new RvnSoloMining.Job(1, template, java.math.BigInteger.ONE.shiftLeft(255), NOW + 30);
        byte[] mix = new byte[32]; for (int i = 0; i < 32; i++) mix[i] = (byte) i;
        var backend = new RvnSoloMining.Backend() {
            public Optional<RvnSoloMining.Job> next() { return Optional.of(job); }
            public boolean current(RvnSoloMining.Job j) { return true; }
            public Optional<RvnSoloMining.Accepted> accept(RvnSoloMining.Job j, RvnBlock block) {
                byte[] actual = block.header().mixHashWire();
                for (int i = 0; i < 32; i++) assertEquals(mix[31-i], actual[i]);
                return Optional.of(new RvnSoloMining.Accepted(block));
            }
            public void relay(RvnSoloMining.Accepted b) {}
        };
        var miner = new RvnSoloMining(backend, (height, hash, nonce, count, target) -> {
            byte[] wire = template.header().kawpowInputDigest();
            for (int i = 0; i < 32; i++) assertEquals(wire[31-i], hash[i]);
            return new RvnCudaKawpowWorker.Batch(List.of(new RvnCudaKawpowWorker.Candidate(nonce, mix, new byte[32])), false, count);
        }, () -> NOW, 1);
        assertEquals(RvnSoloMining.State.ACCEPTED, miner.advance().state());
    }
}

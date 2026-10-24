package ru.bitcoin.node.rvn.app.mining;

import ru.bitcoin.node.rvn.app.chain.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.storage.*;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.*;

/** Local empty-block solo backend. No fees or mempool dependency. The readiness
 * policy must certify FULL parent validation, not just equality of header/coin
 * tips. Spring registration keeps the validator capability gate closed until
 * complete historical admission is implemented and independently verified. */
public final class RvnEmptyBlockMiningBackend implements RvnSoloMining.Backend {
    @FunctionalInterface public interface Relay { void publish(RvnHash256 id, RvnBlock block) throws IOException; }
    private final RvnHeaderStore headers;
    private final RvnCoinStore coins;
    private final RvnMainnetHeaderChain chain;
    private final RvnNativeBlockReplay replay;
    private final RvnPayoutAddress payout;
    private final BooleanSupplier fullyValidated;
    private final LongSupplier clock;
    private final Relay relay;
    private long extraNonce = new SecureRandom().nextLong() >>> 1;
    private long generation;
    private RvnSoloMining.Job active;

    public RvnEmptyBlockMiningBackend(RvnHeaderStore headers, RvnCoinStore coins,
            RvnMainnetHeaderChain chain, RvnNativeBlockReplay replay, RvnPayoutAddress payout,
            BooleanSupplier fullyValidated, LongSupplier clock, Relay relay) {
        this.headers = Objects.requireNonNull(headers); this.coins = Objects.requireNonNull(coins);
        this.chain = Objects.requireNonNull(chain); this.replay = Objects.requireNonNull(replay);
        this.payout = Objects.requireNonNull(payout); this.fullyValidated = Objects.requireNonNull(fullyValidated);
        this.clock = Objects.requireNonNull(clock); this.relay = Objects.requireNonNull(relay);
    }
    @Override public Optional<RvnSoloMining.Job> next() throws IOException {
        flushMined(null); // Recover a durable unsent candidate before creating another job.
        // Same lock order as header admission, then replay: chain -> headers -> coins.
        synchronized (chain) { synchronized (headers) { synchronized (coins) {
            if (!ready()) return Optional.empty();
            var parent = headers.best();
            long now = clock.getAsLong();
            long timestamp = Math.max(now, median(parent) + 1);
            if (timestamp >= now + 30) return Optional.empty();
            if (timestamp < ru.bitcoin.node.rvn.protocol.RvnActivation.MAINNET_KAWPOW_TIME)
                return Optional.empty();
            List<RvnStoredHeader> history = new ArrayList<>(); history.add(parent);
            long bits;
            try {
                bits = RvnMainnetDifficulty.expected(timestamp, distance -> {
                    while (history.size() <= distance) {
                        try { history.add(headers.require(history.getLast().parentId())); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                    var h = history.get(distance);
                    return new RvnMainnetDifficulty.Ancestor(h.height(), h.timestamp(), h.bits());
                });
            } catch (UncheckedIOException e) { throw e.getCause(); }
            // Core's versionbits top bits after assets activation; no invented votes.
            var template = RvnBlockTemplateBuilder.build(0x30000000, parent.id().wire(), parent.height() + 1,
                    timestamp, bits, extraNonce, payout, List.of(), 0, true);
            extraNonce = Math.incrementExact(extraNonce);
            RvnNativeTransactionChecks.block(template, context(parent), true, coins);
            active = new RvnSoloMining.Job(generation = Math.incrementExact(generation), template,
                    RvnMainnetDifficulty.POW_LIMIT, now + 30);
            return Optional.of(active);
        } } }
    }
    @Override public boolean current(RvnSoloMining.Job job) throws IOException {
        synchronized (chain) { synchronized (headers) { synchronized (coins) { return currentLocked(job); } } }
    }
    private boolean currentLocked(RvnSoloMining.Job job) throws IOException {
        return active == job && ready() && clock.getAsLong() < job.expiresAtSeconds()
                && headers.best().id().equals(new RvnHash256(job.template().header().previousHashWire()));
    }
    private boolean ready() throws IOException {
        var tip = coins.tip(); var best = headers.best(); long now = clock.getAsLong();
        return fullyValidated.getAsBoolean() && tip.height() == best.height() && tip.id().equals(best.id())
                && best.timestamp() >= now - 720 && best.timestamp() <= now + 720;
    }
    @Override public Optional<RvnSoloMining.Accepted> accept(RvnSoloMining.Job job, RvnBlock block) throws IOException {
        synchronized (chain) { synchronized (headers) { synchronized (coins) {
            if (!currentLocked(job)) return Optional.empty();
            // The GPU may change only nonce/mix; full header PoW is recomputed by chain.accept.
            if (!Arrays.equals(block.header().kawpowInputDigest(), job.template().header().kawpowInputDigest())
                    || !Arrays.equals(RvnBlockCodec.serialize(new RvnBlock(job.template().header(), block.transactions())),
                                     RvnBlockCodec.serialize(job.template())))
                throw new SecurityException("RVN candidate modified mining template");
            var parent = headers.best();
            RvnNativeTransactionChecks.block(block, context(parent), true, coins);
            var admitted = chain.accept(List.of(block.header()));
            var id = admitted.lastId();
            headers.saveDownloadedBody(id, RvnBlockCodec.serialize(block), (bytes, h) ->
                    RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(bytes), true));
            replay.connectMined(id);
            if (!coins.tip().id().equals(id)) throw new IOException("RVN candidate did not connect to coin tip");
            active = null;
            return Optional.of(new RvnSoloMining.Accepted(block));
        } } }
    }
    @Override public void relay(RvnSoloMining.Accepted accepted) throws IOException { flushMined(accepted.block()); }
    private void flushMined(RvnBlock supplied) throws IOException {
        RvnCoinStore.Tip pending; RvnBlock block;
        synchronized (chain) { synchronized (headers) { synchronized (coins) {
            var slot = coins.pendingMinedBlock();
            if (slot.isEmpty()) return;
            pending = slot.get(); var tip = headers.require(coins.tip().id());
            if (pending.height() > tip.height() || !headers.ancestor(tip, pending.height()).id().equals(pending.id()))
                throw new IOException("RVN mined-block outbox is not on the coin branch");
            byte[] bytes = headers.blockBody(pending.id());
            if (bytes == null) throw new IOException("RVN mined-block outbox body missing");
            if (supplied != null && !Arrays.equals(bytes, RvnBlockCodec.serialize(supplied)))
                throw new SecurityException("RVN relay candidate does not match durable outbox");
            block = RvnBlockCodec.parse(bytes);
        } } }
        // Do not hold chain/store locks during network I/O. A reorg can clear
        // the marker; acknowledgement never deletes a different/newer slot.
        relay.publish(pending.id(), block);
        coins.acknowledgeMinedBlock(pending);
    }
    private RvnSpendContext context(RvnStoredHeader parent) throws IOException {
        long mtp = median(parent);
        return new RvnSpendContext(parent.height() + 1, mtp, mtp, true, 0, height -> {
            try { return median(headers.ancestor(parent, height)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }); // Empty templates have no non-coinbase scripts or relative locks.
    }
    private long median(RvnStoredHeader header) throws IOException {
        long[] times = new long[11]; int count = 0;
        while (true) {
            times[count++] = header.timestamp();
            if (count == 11 || header.height() == 0) break;
            header = headers.require(header.parentId());
        }
        Arrays.sort(times, 0, count); return times[count / 2];
    }
}

package ru.bitcoin.node.app.service;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.stratum.job.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shares one validated candidate across sessions and throttles mempool-only refreshes. */
public final class StratumMiningBackend implements MiningBackend {
    private final NodeValidationService validation;
    private final NodeRelayService relay;
    private final NetworkParameters parameters;
    private final AdjustedTime time;
    private final BooleanSupplier ready;
    private final byte[] payout;
    private final long maximumWeight;
    private final FeeRate minimumFee;
    private MiningWork cached;
    private long builtAt;

    public StratumMiningBackend(NodeValidationService validation, NodeRelayService relay, NetworkParameters parameters,
                               AdjustedTime time, BooleanSupplier ready, byte[] payout, long maximumWeight, FeeRate minimumFee) {
        this.validation = Objects.requireNonNull(validation);
        this.relay = Objects.requireNonNull(relay);
        this.parameters = Objects.requireNonNull(parameters);
        this.time = Objects.requireNonNull(time);
        this.ready = Objects.requireNonNull(ready);
        this.payout = payout.clone();
        this.minimumFee = Objects.requireNonNull(minimumFee);
        if (parameters.network() == BitcoinNetwork.SIGNET) throw new IllegalArgumentException("Signet mining requires challenge signing");
        if (payout.length == 0 || payout.length > 10_000) throw new IllegalArgumentException("Configure a payout script");
        if (maximumWeight <= 0 || maximumWeight > 4_000_000) throw new IllegalArgumentException("Invalid mining weight limit");
        this.maximumWeight = maximumWeight;
    }

    @Override public synchronized Optional<MiningWork> work() {
        var tip = validation.activeTip();
        if (!isCurrent(tip.hash())) { cached = null; return Optional.empty(); }
        long elapsed = System.nanoTime() - builtAt;
        if (cached != null && cached.block().header().previousBlockHash().equals(tip.hash())
                && elapsed < Duration.ofSeconds(30).toNanos()
                && (cached.revision() == validation.revision() || elapsed < Duration.ofSeconds(5).toNanos()))
            return Optional.of(cached);
        var snapshot = validation.miningSnapshot(payout,
                new byte[ExtraNonceManager.EXTRANONCE1_SIZE + ExtraNonceManager.EXTRANONCE2_SIZE], maximumWeight, minimumFee);
        if (!isCurrent(snapshot.block().header().previousBlockHash())) { cached = null; return Optional.empty(); }
        cached = new MiningWork(snapshot.block(), snapshot.medianTimePast() + 1, snapshot.revision(),
                parameters.network() != BitcoinNetwork.TESTNET);
        builtAt = System.nanoTime();
        return Optional.of(cached);
    }

    @Override public boolean isCurrent(Hash256 parent) {
        if (!ready.getAsBoolean()) return false;
        var tip = validation.activeTip();
        return tip.hash().equals(parent) && (parameters.network() == BitcoinNetwork.REGTEST
                || tip.header().timestamp().value() >= time.currentTimeSeconds() - 7200);
    }

    @Override public boolean submit(Block block) {
        if (!isCurrent(block.header().previousBlockHash())) return false;
        return relay.submitBlock(block) == BlockProcessingResult.CONNECTED;
    }

    @Override public long currentTimeSeconds() { return time.currentTimeSeconds(); }
}

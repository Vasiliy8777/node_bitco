package ru.bitcoin.node.app.service;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.stratum.job.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shares one validated candidate across sessions until its inputs change. */
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
        if (!ready.getAsBoolean()) { cached = null; return Optional.empty(); }
        var tip = validation.activeTip();
        if (!isCurrent(tip.hash())) { cached = null; return Optional.empty(); }
        long now = time.currentTimeSeconds();
        long minimumDifficultyTime = tip.header().timestamp().value() + 2 * parameters.targetSpacingSeconds();
        if (cached != null && cached.block().header().previousBlockHash().equals(tip.hash())
                && cached.revision() == validation.revision()
                && cached.block().header().timestamp().value() <= now + 7200
                // Testnet difficulty can change with time even with an unchanged parent.
                && (cached.timeRolling() || (cached.block().header().timestamp().value() > minimumDifficultyTime)
                        == (now > minimumDifficultyTime)))
            return Optional.of(cached);
        var snapshot = validation.miningSnapshot(payout,
                new byte[ExtraNonceManager.EXTRANONCE1_SIZE + ExtraNonceManager.EXTRANONCE2_SIZE], maximumWeight, minimumFee);
        if (!isCurrent(snapshot.block().header().previousBlockHash())) { cached = null; return Optional.empty(); }
        cached = new MiningWork(snapshot.block(), snapshot.minimumTimestamp(), snapshot.revision(),
                !parameters.allowMinDifficultyBlocks() || parameters.noRetargeting());
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

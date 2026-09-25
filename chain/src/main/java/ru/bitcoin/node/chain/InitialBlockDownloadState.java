package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Bitcoin Core-style Initial Block Download state.
 *
 * <p>The state starts in IBD and latches permanently to false once the active
 * chain has at least the configured minimum chain work and its tip is recent
 * enough. A later clock change or temporary reorg must not put the process
 * back into IBD.</p>
 */
public final class InitialBlockDownloadState {
    public static final Duration DEFAULT_MAX_TIP_AGE = Duration.ofHours(24);

    private final BigInteger minimumChainWork;
    private final long maxTipAgeSeconds;
    private final LongSupplier currentTimeSeconds;
    private final AtomicBoolean initialBlockDownload = new AtomicBoolean(true);

    public InitialBlockDownloadState(NetworkParameters parameters, LongSupplier currentTimeSeconds) {
        this(parameters.minimumChainWork(), DEFAULT_MAX_TIP_AGE, currentTimeSeconds);
    }

    public InitialBlockDownloadState(BigInteger minimumChainWork, Duration maxTipAge, LongSupplier currentTimeSeconds) {
        this.minimumChainWork = Objects.requireNonNull(minimumChainWork, "minimumChainWork");
        Objects.requireNonNull(maxTipAge, "maxTipAge");
        this.currentTimeSeconds = Objects.requireNonNull(currentTimeSeconds, "currentTimeSeconds");
        if (minimumChainWork.signum() < 0) throw new IllegalArgumentException("minimumChainWork must not be negative");
        if (maxTipAge.isNegative()) throw new IllegalArgumentException("maxTipAge must not be negative");
        this.maxTipAgeSeconds = maxTipAge.getSeconds();
    }

    /** Updates the latch from the current active tip and returns the resulting IBD state. */
    public boolean update(BlockIndex activeTip) {
        Objects.requireNonNull(activeTip, "activeTip");
        if (!initialBlockDownload.get()) return false;
        if (activeTip.chainWork().compareTo(minimumChainWork) < 0) return true;

        long now = currentTimeSeconds.getAsLong();
        long tipTime = activeTip.header().timestamp().value();
        // Match the intent of Core's IsTipRecent: a tip at or newer than
        // now-max_tip_age is recent. Future timestamps are therefore recent too;
        // contextual header-time rules provide their separate consensus bound.
        if (tipTime < now - maxTipAgeSeconds) return true;

        initialBlockDownload.compareAndSet(true, false);
        return initialBlockDownload.get();
    }

    public boolean isInitialBlockDownload() {
        return initialBlockDownload.get();
    }
}

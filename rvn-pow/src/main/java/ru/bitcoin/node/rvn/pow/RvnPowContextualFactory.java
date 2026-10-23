package ru.bitcoin.node.rvn.pow;

import ru.bitcoin.node.rvn.consensus.*;

import java.math.BigInteger;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Binds PoW checks to the SAME staged index used for difficulty and MTP.
 * Requires a real expected-nBits implementation; never supplies a permissive one.
 */
public final class RvnPowContextualFactory
        implements RvnValidatedHeaderIndex.ContextualVerifierFactory {
    private final RvnPowRouter router;
    private final RvnHeaderContextPolicy.ExpectedBits difficulty;
    private final RvnHash256 anchor;
    private final long anchorHeight, anchorTimestamp, futureDrift;
    private final BigInteger powLimit;
    private final RvnPowDigest.ByteOrder order;
    private final LongSupplier clock;

    public RvnPowContextualFactory(RvnPowRouter router,
                                   RvnHeaderContextPolicy.ExpectedBits difficulty,
                                   RvnHash256 anchor, long anchorHeight, long anchorTimestamp,
                                   long futureDrift, BigInteger powLimit, RvnPowDigest.ByteOrder order,
                                   LongSupplier clock) {
        this.router = Objects.requireNonNull(router);
        this.difficulty = Objects.requireNonNull(difficulty);
        this.anchor = Objects.requireNonNull(anchor);
        if (anchorHeight < 0) throw new IllegalArgumentException("anchorHeight");
        this.anchorHeight = anchorHeight;
        this.anchorTimestamp = anchorTimestamp;
        this.futureDrift = futureDrift;
        this.powLimit = Objects.requireNonNull(powLimit);
        this.order = Objects.requireNonNull(order);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RvnHeaderProofGate.Verifier forIndex(RvnValidatedHeaderIndex staged) {
        var checks = new RvnPowConsensusVerifier(router, staged, anchor, anchorHeight, powLimit, order);
        return new RvnHeaderContextVerifierFactory(checks, checks, difficulty,
                anchor, anchorTimestamp, futureDrift, clock).forIndex(staged);
    }
}

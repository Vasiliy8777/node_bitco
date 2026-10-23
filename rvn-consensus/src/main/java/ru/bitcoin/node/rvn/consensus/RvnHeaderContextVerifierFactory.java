package ru.bitcoin.node.rvn.consensus;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Binds block-ID and PoW checkers plus contextual policy to the STAGED index.
 * Callers must provide real Ravencoin consensus hash, PoW and difficulty engines.
 */
public final class RvnHeaderContextVerifierFactory
        implements RvnValidatedHeaderIndex.ContextualVerifierFactory {
    private final RvnHeaderProofVerifierAdapter.BlockIdChecker ids;
    private final RvnHeaderProofVerifierAdapter.PowChecker pow;
    private final RvnHeaderContextPolicy.ExpectedBits difficulty;
    private final RvnHash256 anchor;
    private final long anchorTimestamp, futureDrift;
    private final LongSupplier clock;

    public RvnHeaderContextVerifierFactory(RvnHeaderProofVerifierAdapter.BlockIdChecker ids,
                                           RvnHeaderProofVerifierAdapter.PowChecker pow,
                                           RvnHeaderContextPolicy.ExpectedBits difficulty,
                                           RvnHash256 anchor, long anchorTimestamp, long futureDrift, LongSupplier clock) {
        this.ids = Objects.requireNonNull(ids);
        this.pow = Objects.requireNonNull(pow);
        this.difficulty = Objects.requireNonNull(difficulty);
        this.anchor = Objects.requireNonNull(anchor);
        if (anchorTimestamp < 0 || anchorTimestamp > 0xffff_ffffL || futureDrift < 0 || futureDrift > 86400)
            throw new IllegalArgumentException("Invalid timestamp parameters");
        this.anchorTimestamp = anchorTimestamp;
        this.futureDrift = futureDrift;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RvnHeaderProofGate.Verifier forIndex(RvnValidatedHeaderIndex staged) {
        return new RvnHeaderProofVerifierAdapter(ids, pow,
                new RvnHeaderContextPolicy(staged, anchor, anchorTimestamp, futureDrift, clock, difficulty));
    }
}

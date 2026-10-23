package ru.bitcoin.node.rvn.consensus;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Context checks for a candidate against a verified ancestor index.
 * Expected nBits MUST be supplied by an independently correct RVN difficulty engine.
 * This class does not implement DGW or historical algorithm transitions.
 */
public final class RvnHeaderContextPolicy {
    @FunctionalInterface
    public interface ExpectedBits {
        long expected(RvnHeaderCandidate candidate, RvnValidatedHeaderIndex index);
    }

    private final RvnValidatedHeaderIndex index;
    private final RvnHash256 anchor;
    private final long anchorTimestamp;
    private final long futureDrift;
    private final LongSupplier clockSeconds;
    private final ExpectedBits difficulty;

    public RvnHeaderContextPolicy(RvnValidatedHeaderIndex index, RvnHash256 anchor,
                                  long anchorTimestamp, long futureDrift,
                                  LongSupplier clockSeconds, ExpectedBits difficulty) {
        this.index = Objects.requireNonNull(index);
        this.anchor = Objects.requireNonNull(anchor);
        this.anchorTimestamp = anchorTimestamp;
        if (anchorTimestamp < 0 || anchorTimestamp > 0xffff_ffffL
                || futureDrift < 0 || futureDrift > 86400)
            throw new IllegalArgumentException("Invalid context parameters");
        this.futureDrift = futureDrift;
        this.clockSeconds = Objects.requireNonNull(clockSeconds);
        this.difficulty = Objects.requireNonNull(difficulty);
    }

    public void requireValid(RvnHeaderCandidate candidate) {
        Objects.requireNonNull(candidate);
        long expected = difficulty.expected(candidate, index);
        if (expected < 0 || expected > 0xffff_ffffL || candidate.bits() != expected)
            throw new SecurityException("Unexpected nBits");
        long median = RvnHeaderTimeRules.medianTimePast(index, candidate.parentId(),
                anchor, anchorTimestamp);
        RvnHeaderTimeRules.requireTimestamp(candidate.timestamp(), median,
                clockSeconds.getAsLong(), futureDrift);
    }
}

package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Combines three independent production-required checks. Neither hashing nor
 * difficulty rules are invented here; callers must provide real implementations.
 */
public final class RvnHeaderProofVerifierAdapter implements RvnHeaderProofGate.Verifier {
    @FunctionalInterface
    public interface BlockIdChecker {
        boolean matches(RvnHeaderCandidate candidate, byte[] raw);
    }

    @FunctionalInterface
    public interface PowChecker {
        boolean valid(RvnHeaderCandidate candidate, byte[] raw);
    }

    private final BlockIdChecker ids;
    private final PowChecker pow;
    private final RvnHeaderContextPolicy context;

    public RvnHeaderProofVerifierAdapter(BlockIdChecker ids, PowChecker pow,
                                         RvnHeaderContextPolicy context) {
        this.ids = Objects.requireNonNull(ids);
        this.pow = Objects.requireNonNull(pow);
        this.context = Objects.requireNonNull(context);
    }

    @Override
    public boolean blockIdMatches(RvnHeaderCandidate candidate, byte[] raw) {
        return ids.matches(candidate, raw.clone());
    }

    @Override
    public boolean validProofOfWork(RvnHeaderCandidate candidate, byte[] raw) {
        return pow.valid(candidate, raw.clone());
    }

    @Override
    public boolean validContext(RvnHeaderCandidate candidate) {
        context.requireValid(candidate);
        return true;
    }
}

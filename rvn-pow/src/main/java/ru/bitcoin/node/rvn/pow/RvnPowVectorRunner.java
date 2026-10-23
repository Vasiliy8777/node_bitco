package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * Fail closed unless each algorithm has an independently provided matching vector.
 */
public final class RvnPowVectorRunner {
    private RvnPowVectorRunner() {
    }

    public static void verify(RvnPowEngine engine, List<RvnPowVector> vectors)
            throws RvnPowException {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(vectors);
        EnumSet<RvnPowAlgorithm> covered = EnumSet.noneOf(RvnPowAlgorithm.class);
        Set<String> names = new HashSet<>();
        for (RvnPowVector v : vectors) {
            Objects.requireNonNull(v);
            if (v.name().isBlank() || !names.add(v.name()))
                throw new RvnPowException("Duplicate or blank vector name");
            final RvnPowResult actual;
            try {
                actual = engine.calculate(v.algorithm(), v.raw());
            } catch (RuntimeException e) {
                throw new RvnPowException("PoW vector execution failed: " + v.name(), e);
            }
            if (actual == null || !Arrays.equals(actual.blockIdWire(), v.expectedIdWire())
                    || !Arrays.equals(actual.powDigestWire(), v.expectedPowWire()))
                throw new RvnPowException("RVN PoW vector mismatch: " + v.name());
            covered.add(v.algorithm());
        }
        if (covered.size() != RvnPowAlgorithm.values().length)
            throw new RvnPowException("Missing reference vectors: " + EnumSet.complementOf(covered));
    }
}

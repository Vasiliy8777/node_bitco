package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * Fail-closed self-test for every algorithm the selected engine claims to support.
 */
public final class RvnPowVectorRunner {
    private RvnPowVectorRunner() {
    }

    public static void verify(RvnPowEngine engine, List<RvnPowVector> vectors)
            throws RvnPowException {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(vectors);
        EnumSet<RvnPowAlgorithm> covered = EnumSet.noneOf(RvnPowAlgorithm.class);
        for (RvnPowVector vector : vectors) {
            Objects.requireNonNull(vector);
            RvnPowResult actual = engine.calculate(vector.algorithm(), vector.raw());
            if (actual == null || !Arrays.equals(actual.blockIdWire(), vector.expectedIdWire())
                    || !Arrays.equals(actual.powDigestWire(), vector.expectedPowWire()))
                throw new RvnPowException("RVN PoW vector mismatch: " + vector.name());
            covered.add(vector.algorithm());
        }
        if (covered.size() != RvnPowAlgorithm.values().length)
            throw new RvnPowException("Missing verified test vectors for " + EnumSet.complementOf(covered));
    }
}

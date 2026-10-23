package ru.bitcoin.node.rvn.pow;

import java.util.List;
import java.util.Objects;

/**
 * Constructor requires all algorithm self-tests to pass before exposing engine.
 */
public final class RvnPowValidatedEngine implements RvnPowEngine {
    private final RvnPowEngine delegate;

    public RvnPowValidatedEngine(RvnPowEngine delegate, List<RvnPowVector> trustedVectors)
            throws RvnPowException {
        this.delegate = Objects.requireNonNull(delegate);
        RvnPowVectorRunner.verify(delegate, trustedVectors);
    }

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] raw)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(algorithm, raw);
        return delegate.calculate(algorithm, raw.clone());
    }
}

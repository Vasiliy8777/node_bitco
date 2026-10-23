package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * Explicit per-era engine wiring; KawPoW must expose independent mix verification.
 */
public final class RvnPowMultiEngine implements RvnKawpowMixEngine {
    private final RvnPowAlgorithmEngine x16r, x16rv2;
    private final RvnKawpowMixEngine kawpow;

    public RvnPowMultiEngine(RvnPowAlgorithmEngine x16r, RvnPowAlgorithmEngine x16rv2,
                             RvnKawpowMixEngine kawpow) {
        this.x16r = Objects.requireNonNull(x16r);
        this.x16rv2 = Objects.requireNonNull(x16rv2);
        this.kawpow = Objects.requireNonNull(kawpow);
    }

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] header)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(algorithm, header);
        return switch (algorithm) {
            case X16R -> Objects.requireNonNull(x16r.calculate(header.clone()));
            case X16RV2 -> Objects.requireNonNull(x16rv2.calculate(header.clone()));
            case KAWPOW -> RvnKawpowProof.requireValidMix(kawpow, header);
        };
    }

    @Override
    public RvnKawpowMixResult calculateKawpow(byte[] header)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.KAWPOW, header);
        return Objects.requireNonNull(kawpow.calculateKawpow(header.clone()));
    }
}

package ru.bitcoin.node.rvn.pow;

/**
 * Production-safe default: no algorithm implementation means no accepted blocks.
 */
public final class RvnPowUnavailable implements RvnPowEngine {
    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] rawHeader)
            throws RvnPowException {
        throw new RvnPowException("Ravencoin PoW implementation unavailable: " + algorithm);
    }
}

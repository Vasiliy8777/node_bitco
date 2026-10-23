package ru.bitcoin.node.rvn.pow;

/**
 * Must calculate actual Ravencoin block ID and actual PoW digest. NEVER return
 * placeholder SHA256d values or unverified data.
 */
public interface RvnPowEngine {
    RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] rawHeader) throws RvnPowException;
}

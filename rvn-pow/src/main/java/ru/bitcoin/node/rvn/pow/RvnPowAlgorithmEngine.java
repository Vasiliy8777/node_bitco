package ru.bitcoin.node.rvn.pow;

/**
 * Strict algorithm-specific provider; no fallback between PoW eras.
 */
@FunctionalInterface
public interface RvnPowAlgorithmEngine {
    RvnPowResult calculate(byte[] header) throws RvnPowException;
}

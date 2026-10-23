package ru.bitcoin.node.rvn.pow;

/**
 * Real KawPoW computation must independently derive the mix hash from header/nonce/DAG.
 */
public interface RvnKawpowMixEngine extends RvnPowEngine {
    RvnKawpowMixResult calculateKawpow(byte[] rawHeader) throws RvnPowException;
}

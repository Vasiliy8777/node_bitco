package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * Critical consensus requirement: compare independently computed mix with the
 * 32-byte mix embedded in the header. Never trust the supplied header mix.
 */
public final class RvnKawpowProof {
    private RvnKawpowProof() {
    }

    public static RvnPowResult requireValidMix(RvnKawpowMixEngine engine, byte[] raw)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.KAWPOW, raw);
        var computed = engine.calculateKawpow(raw.clone());
        if (computed == null || !Arrays.equals(computed.mixHashWire(),
                Arrays.copyOfRange(raw, 88, 120)))
            throw new RvnPowException("KawPoW computed mix does not match header mix");
        return computed.result();
    }
}

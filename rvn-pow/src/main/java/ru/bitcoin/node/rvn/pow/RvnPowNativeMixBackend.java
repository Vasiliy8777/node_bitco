package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;

/**
 * KawPoW JNI mix ABI. No native implementation is bundled.
 * Result is 96 bytes: blockIdWire(32) | powDigestWire(32) | mixHashWire(32).
 */
public final class RvnPowNativeMixBackend implements RvnKawpowMixEngine {
    public RvnPowNativeMixBackend(String libraryName) throws RvnPowException {
        if (libraryName == null || !libraryName.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Invalid native library name");
        try {
            System.loadLibrary(libraryName);
        } catch (LinkageError | SecurityException e) {
            throw new RvnPowException("Native KawPoW implementation unavailable", e);
        }
    }

    private static native byte[] calculateKawpowNative(byte[] header);

    @Override
    public RvnKawpowMixResult calculateKawpow(byte[] header)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.KAWPOW, header);
        final byte[] out;
        try {
            out = calculateKawpowNative(header.clone());
        } catch (LinkageError | RuntimeException e) {
            throw new RvnPowException("Native KawPoW execution failed", e);
        }
        if (out == null || out.length != 96)
            throw new RvnPowException("Invalid native KawPoW output");
        return new RvnKawpowMixResult(Arrays.copyOfRange(out, 64, 96),
                new RvnPowResult(Arrays.copyOfRange(out, 0, 32),
                        Arrays.copyOfRange(out, 32, 64)));
    }

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] header)
            throws RvnPowException {
        if (algorithm != RvnPowAlgorithm.KAWPOW)
            throw new RvnPowException("Native KawPoW engine cannot calculate " + algorithm);
        return RvnKawpowProof.requireValidMix(this, header);
    }
}

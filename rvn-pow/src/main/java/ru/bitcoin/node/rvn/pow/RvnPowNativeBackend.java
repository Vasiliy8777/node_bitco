package ru.bitcoin.node.rvn.pow;

/**
 * JNI entrypoint for an externally supplied, independently audited Ravencoin
 * native implementation. Merely loading a library does NOT make it trusted.
 */
public final class RvnPowNativeBackend implements RvnPowEngine {
    private final boolean loaded;

    public RvnPowNativeBackend(String libraryName) throws RvnPowException {
        if (libraryName == null || !libraryName.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Native library name");
        try {
            System.loadLibrary(libraryName);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            throw new RvnPowException("Native RVN PoW library unavailable", e);
        }
        loaded = true;
    }

    private static native byte[] calculateNative(int algorithm, byte[] header);

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] raw)
            throws RvnPowException {
        RvnPowHeaderRules.requireHeader(algorithm, raw);
        if (!loaded) throw new RvnPowException("Native library not loaded");
        final byte[] result;
        try {
            result = calculateNative(algorithm.ordinal(), raw.clone());
        } catch (LinkageError | RuntimeException e) {
            throw new RvnPowException("Native RVN PoW failure", e);
        }
        if (result == null || result.length != 64)
            throw new RvnPowException("Native RVN PoW returned invalid digest size");
        return new RvnPowResult(java.util.Arrays.copyOfRange(result, 0, 32),
                java.util.Arrays.copyOfRange(result, 32, 64));
    }
}

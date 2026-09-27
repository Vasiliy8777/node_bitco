package ru.bitcoin.node.consensus.deployment;

/** BIP9 nVersion bit encoding helpers. */
public final class VersionBits {
    public static final int TOP_MASK = 0xE0000000;
    public static final int TOP_BITS = 0x20000000;

    private VersionBits() { }

    public static int mask(Deployment deployment) {
        if (deployment == null) throw new IllegalArgumentException("deployment must not be null");
        return 1 << deployment.bit();
    }

    public static boolean signals(int version, Deployment deployment) {
        return (version & TOP_MASK) == TOP_BITS && (version & mask(deployment)) != 0;
    }

    public static int signal(int version, Deployment deployment) {
        return (version & ~TOP_MASK) | TOP_BITS | mask(deployment);
    }
}

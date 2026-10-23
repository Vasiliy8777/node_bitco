package ru.bitcoin.node.rvn.protocol;

/**
 * Timestamp boundary for wire header format; not a complete deployment/consensus engine.
 */
public final class RvnActivation {
    public static final long MAINNET_KAWPOW_TIME = 1_588_788_000L;

    private RvnActivation() {
    }

    public static long mainnetTime() {
        return MAINNET_KAWPOW_TIME;
    }
}

package ru.bitcoin.node.rvn.pow;

/**
 * KawPoW epoch numbering (7500 blocks/epoch), not the Ethash 30000-block epoch.
 */
public final class RvnKawpowEpoch {
    public static final long EPOCH_LENGTH = 7500;

    private RvnKawpowEpoch() {
    }

    public static long fromHeight(long height) {
        if (height < 0) throw new IllegalArgumentException("height");
        return height / EPOCH_LENGTH;
    }

    public static long startHeight(long epoch) {
        if (epoch < 0) throw new IllegalArgumentException("epoch");
        return Math.multiplyExact(epoch, EPOCH_LENGTH);
    }
}

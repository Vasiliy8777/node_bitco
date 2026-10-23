package ru.bitcoin.node.rvn.pow;

/**
 * Separates the computed KawPoW mix from the final 256-bit PoW result.
 */
public final class RvnKawpowMixResult {
    private final byte[] mixHashWire;
    private final RvnPowResult result;

    public RvnKawpowMixResult(byte[] mixHashWire, RvnPowResult result) {
        if (mixHashWire == null || mixHashWire.length != 32)
            throw new IllegalArgumentException("32-byte mix hash required");
        this.mixHashWire = mixHashWire.clone();
        this.result = java.util.Objects.requireNonNull(result);
    }

    public byte[] mixHashWire() {
        return mixHashWire.clone();
    }

    public RvnPowResult result() {
        return result;
    }
}

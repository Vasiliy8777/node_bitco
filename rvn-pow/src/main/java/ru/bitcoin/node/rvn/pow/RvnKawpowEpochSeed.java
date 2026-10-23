package ru.bitcoin.node.rvn.pow;

/**
 * KawPoW Ethash-style epoch seed. Seed(0)=32 zero bytes;
 * Seed(n+1)=legacy Keccak-256(Seed(n)).
 */
public final class RvnKawpowEpochSeed {
    private RvnKawpowEpochSeed() {
    }

    public static byte[] forEpoch(long epoch) {
        if (epoch < 0) throw new IllegalArgumentException("Negative epoch");
        // Guard untrusted inputs: this API is not an unbounded CPU work primitive.
        if (epoch > 1_000_000) throw new IllegalArgumentException("Epoch too large");
        byte[] seed = new byte[32];
        for (long i = 0; i < epoch; i++) seed = RvnKeccak256.digest(seed);
        return seed;
    }

    public static byte[] forHeight(long height) {
        return forEpoch(RvnKawpowEpoch.fromHeight(height));
    }
}

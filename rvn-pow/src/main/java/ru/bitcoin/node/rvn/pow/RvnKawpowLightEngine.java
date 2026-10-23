package ru.bitcoin.node.rvn.pow;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** CPU light verifier; retains one epoch cache. Not a GPU mining worker. */
public final class RvnKawpowLightEngine implements RvnKawpowMixEngine {
    private final int maxCachedItems;
    private long epoch = -1;
    private RvnProgpowRoundEngine rounds;

    public RvnKawpowLightEngine(int maxCachedItems) {
        if (maxCachedItems < 0) throw new IllegalArgumentException("Negative DAG cache capacity");
        this.maxCachedItems = maxCachedItems;
    }

    /** Serializes epoch replacement and bounds retained memory to one epoch. */
    public synchronized Computation compute(long height, byte[] headerHash, long nonce) {
        if (height < 0 || height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Height outside Core int range");
        int[] seed = RvnKawpowHash.seed(headerHash, nonce);
        long requested = RvnKawpowEpoch.fromHeight(height);
        if (rounds == null || epoch != requested) {
            rounds = null;
            var dag = RvnKawpowLightDag.forEpoch(requested, maxCachedItems);
            rounds = new RvnProgpowRoundEngine(dag, RvnProgpowL1Cache.fromDag(dag));
            epoch = requested;
        }
        byte[] mix = rounds.calculate(height, seed[0], seed[1]);
        return new Computation(mix, RvnKawpowHash.finish(seed, mix));
    }

    @Override
    public RvnKawpowMixResult calculateKawpow(byte[] header) throws RvnPowException {
        var view = new RvnKawpowHeaderView(header);
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            byte[] preimage = Arrays.copyOf(view.raw(), 80);
            byte[] headerHash = sha.digest(sha.digest(preimage));
            reverse(headerHash); // Core feeds to_hash256(nHeaderHash.GetHex()).
            var computed = compute(view.declaredHeight(), headerHash, view.nonceBits());
            // Core converts the Ethash big-endian result to uint256 wire order.
            byte[] wire = computed.finalHash();
            reverse(wire);
            byte[] mixWire = computed.mixHash();
            reverse(mixWire);
            return new RvnKawpowMixResult(mixWire, new RvnPowResult(wire, wire));
        } catch (NoSuchAlgorithmException e) {
            throw new RvnPowException("SHA-256 unavailable", e);
        }
    }

    private static void reverse(byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            byte v = bytes[i]; bytes[i] = bytes[bytes.length - 1 - i]; bytes[bytes.length - 1 - i] = v;
        }
    }

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] header) throws RvnPowException {
        if (algorithm != RvnPowAlgorithm.KAWPOW)
            throw new RvnPowException("Light KawPoW engine cannot calculate " + algorithm);
        return RvnKawpowProof.requireValidMix(this, header);
    }

    public record Computation(byte[] mixHash, byte[] finalHash) {
        public Computation {
            if (mixHash == null || mixHash.length != 32 || finalHash == null || finalHash.length != 32)
                throw new IllegalArgumentException("32-byte hashes required");
            mixHash = mixHash.clone(); finalHash = finalHash.clone();
        }
        @Override public byte[] mixHash() { return mixHash.clone(); }
        @Override public byte[] finalHash() { return finalHash.clone(); }
    }
}

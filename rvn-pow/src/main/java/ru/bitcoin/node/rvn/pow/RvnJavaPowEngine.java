package ru.bitcoin.node.rvn.pow;

import fr.cryptohash.*;
import java.util.Objects;
import java.util.function.Supplier;

/** Pure Java era engines. Each invocation owns its mutable digest instances. */
public final class RvnJavaPowEngine implements RvnKawpowMixEngine {
    private final RvnX16RChain x16r;
    private final RvnX16Rv2Chain x16rv2;
    private final RvnKawpowLightEngine kawpow;

    public RvnJavaPowEngine(RvnKawpowLightEngine kawpow) throws RvnPowException {
        this.kawpow = Objects.requireNonNull(kawpow);
        var primitives = primitives().requireComplete();
        x16r = new RvnX16RChain(primitives);
        x16rv2 = new RvnX16Rv2Chain(primitives, input -> new Tiger().digest(input));
    }

    /** Explicitly complete registry; the existing empty/default registry stays fail-closed. */
    public static RvnX16RPrimitiveRegistry primitives() {
        return new RvnX16RPrimitiveRegistry()
                .set(0, primitive(BLAKE512::new))
                .set(1, primitive(BMW512::new))
                .set(2, primitive(Groestl512::new))
                .set(3, primitive(JH512::new))
                .set(5, primitive(Skein512::new))
                .set(6, primitive(Luffa512::new))
                .set(7, primitive(CubeHash512::new))
                .set(8, primitive(SHAvite512::new))
                .set(9, primitive(SIMD512::new))
                .set(10, primitive(ECHO512::new))
                .set(11, primitive(Hamsi512::new))
                .set(12, primitive(Fugue512::new))
                .set(13, primitive(Shabal512::new))
                .set(14, primitive(Whirlpool::new));
    }

    private static RvnX16RPrimitive primitive(Supplier<Digest> factory) {
        return input -> {
            if (input == null) throw new RvnPowException("Null hash input");
            return factory.get().digest(input);
        };
    }

    @Override
    public RvnPowResult calculate(RvnPowAlgorithm algorithm, byte[] header) throws RvnPowException {
        Objects.requireNonNull(algorithm);
        RvnPowHeaderRules.requireHeader(algorithm, header);
        byte[] raw = header.clone();
        if (algorithm == RvnPowAlgorithm.KAWPOW)
            return RvnKawpowProof.requireValidMix(kawpow, raw);
        byte[] digest = switch (algorithm) {
            case X16R -> x16r.digest(raw);
            case X16RV2 -> x16rv2.digest(raw);
            case KAWPOW -> throw new AssertionError("KawPoW already dispatched");
        };
        return new RvnPowResult(digest, digest);
    }

    @Override
    public RvnKawpowMixResult calculateKawpow(byte[] header) throws RvnPowException {
        return kawpow.calculateKawpow(header);
    }
}

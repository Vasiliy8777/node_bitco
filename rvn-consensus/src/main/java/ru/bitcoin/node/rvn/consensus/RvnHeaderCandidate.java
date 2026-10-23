package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Metadata supplied by an external proof verifier. NOT validated by construction.
 */
public record RvnHeaderCandidate(RvnHash256 id, RvnHash256 parentId, long bits,
                                 long timestamp, long declaredHeight, boolean kawpow) {
    public RvnHeaderCandidate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(parentId);
        if (id.isZero() || (bits & ~0xffffffffL) != 0 || timestamp < 0 || timestamp > 0xffffffffL
                || declaredHeight < -1 || declaredHeight > 0xffffffffL) throw new IllegalArgumentException("candidate");
        if (kawpow && declaredHeight < 0) throw new IllegalArgumentException("missing KawPoW height");
    }
}

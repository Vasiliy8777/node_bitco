package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * Executes the 16-round X16R chain with caller-supplied, independently verified
 * implementations of all sixteen exact 512-bit primitives. Does not substitute
 * similarly named SHA3/JCA algorithms for Ravencoin's specified primitives.
 */
public final class RvnX16RChain {
    private final RvnX16RPrimitive[] primitives;

    public RvnX16RChain(RvnX16RPrimitive[] primitives) {
        if (primitives == null || primitives.length != 16)
            throw new IllegalArgumentException("All 16 primitives required");
        this.primitives = primitives.clone();
        for (var p : this.primitives) Objects.requireNonNull(p, "primitive");
    }

    public byte[] digest(byte[] header) throws RvnPowException {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.X16R, header);
        byte[] state = header.clone();
        for (int id : RvnX16RSequence.fromHeader(header)) {
            byte[] next = primitives[id].digest(state.clone());
            if (next == null || next.length != 64)
                throw new RvnPowException("X16R primitive " + id + " returned invalid 512-bit output");
            state = next.clone();
        }
        return Arrays.copyOf(state, 32);
    }
}

package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * X16RV2's Tiger substitution pipeline.
 * X16RV2 changes the KECCAK, LUFFA and SHA512 rounds to
 * TIGER192(input) followed by the original 512-bit primitive.
 * This class requires externally supplied exact primitive implementations.
 */
public final class RvnX16Rv2Chain {
    private final RvnX16RPrimitive[] primitives;
    private final RvnX16Rv2Primitive tiger;

    public RvnX16Rv2Chain(RvnX16RPrimitive[] primitives, RvnX16Rv2Primitive tiger) {
        if (primitives == null || primitives.length != 16)
            throw new IllegalArgumentException("16 primitives required");
        this.primitives = primitives.clone();
        for (var primitive : this.primitives) Objects.requireNonNull(primitive);
        this.tiger = Objects.requireNonNull(tiger);
    }

    public byte[] digest(byte[] header) throws RvnPowException {
        RvnPowHeaderRules.requireHeader(RvnPowAlgorithm.X16RV2, header);
        byte[] state = header.clone();
        for (int id : RvnX16RSequence.fromHeader(header)) {
            byte[] input = state.clone();
            if (id == 4 || id == 6 || id == 15) {
                byte[] t = tiger.tiger192(input);
                if (t == null || t.length != 24)
                    throw new RvnPowException("Invalid TIGER192 output");
                input = new byte[64];
                System.arraycopy(t, 0, input, 0, t.length);
            }
            byte[] next = primitives[id].digest(input.clone());
            if (next == null || next.length != 64)
                throw new RvnPowException("Invalid X16RV2 512-bit primitive output: " + id);
            state = next.clone();
        }
        return Arrays.copyOf(state, 32);
    }
}

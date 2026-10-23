package ru.bitcoin.node.rvn.pow;

import java.util.*;

/**
 * Exact algorithm-ID registry. Missing primitives are an error, never
 * replaced by SHA512, SHA3, or a placeholder digest.
 */
public final class RvnX16RPrimitiveRegistry {
    private final RvnX16RPrimitive[] primitives = new RvnX16RPrimitive[16];

    public RvnX16RPrimitiveRegistry() {
        primitives[4] = new RvnKeccak512Primitive();
        primitives[15] = new RvnSha512Primitive();
    }

    public RvnX16RPrimitiveRegistry set(int algorithmId, RvnX16RPrimitive primitive) {
        if (algorithmId < 0 || algorithmId >= 16) throw new IllegalArgumentException("algorithmId");
        primitives[algorithmId] = Objects.requireNonNull(primitive);
        return this;
    }

    public RvnX16RPrimitive[] requireComplete() throws RvnPowException {
        for (int i = 0; i < primitives.length; i++)
            if (primitives[i] == null) throw new RvnPowException(
                    "Missing audited X16R primitive " + i + " (" + RvnX16RSequence.ALGORITHMS[i] + ")");
        return primitives.clone();
    }

    public List<String> missing() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < 16; i++)
            if (primitives[i] == null)
                result.add(RvnX16RSequence.ALGORITHMS[i]);
        return List.copyOf(result);
    }
}

package ru.bitcoin.node.rvn.pow;

import java.util.Arrays;
import java.util.Objects;

/**
 * Compare any optimized DAG provider with the existing light-cache reference.
 */
public final class RvnKawpowDagSelfCheck {
    private RvnKawpowDagSelfCheck() {
    }

    public static void verify(RvnKawpowDagReader candidate, RvnKawpowCache reference,
                              long... indexes) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(indexes, "indexes");
        for (long index : indexes) {
            if (index < 0 || index >= candidate.itemCount())
                throw new IndexOutOfBoundsException("DAG index " + index);
            byte[] actual = candidate.item(index);
            byte[] expected = RvnKawpowDatasetItem.dagItem(reference, index);
            if (!Arrays.equals(expected, actual))
                throw new IllegalStateException("DAG mismatch at item " + index);
        }
    }
}

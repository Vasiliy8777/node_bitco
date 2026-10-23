package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Bounded admission of independently verified candidates in parent-first order.
 * A failure stops processing; already admitted headers remain in the index.
 */
public final class RvnHeaderBatchAdmission {
    public record Item(RvnHeaderCandidate candidate, byte[] raw) {
        public Item {
            Objects.requireNonNull(candidate);
            raw = Objects.requireNonNull(raw).clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }

    private RvnHeaderBatchAdmission() {
    }

    /**
     * Performs whole-batch structural checks before any index mutation.
     * Proof validation still happens sequentially and may partially commit.
     */
    public static List<RvnValidatedHeaderIndex.Entry> admitPreflighted(
            RvnValidatedHeaderIndex index, List<Item> batch,
            RvnHeaderProofGate.Verifier verifier, RvnHeaderFormatPolicy policy) {
        RvnHeaderBatchPreflight.check(batch, policy);
        return admit(index, batch, verifier);
    }


    /**
     * Atomic alternative to the legacy sequential admission API.
     */
    public static List<RvnValidatedHeaderIndex.Entry> admitAtomic(
            RvnValidatedHeaderIndex index, List<Item> batch,
            RvnHeaderProofGate.Verifier verifier, RvnHeaderFormatPolicy policy) {
        return Objects.requireNonNull(index).admitBatchAtomic(batch, verifier, policy);
    }

    public static List<RvnValidatedHeaderIndex.Entry> admit(
            RvnValidatedHeaderIndex index, List<Item> batch,
            RvnHeaderProofGate.Verifier verifier) {
        Objects.requireNonNull(index);
        Objects.requireNonNull(batch);
        Objects.requireNonNull(verifier);
        if (batch.size() > 2000) throw new IllegalArgumentException("headers batch too large");
        List<RvnValidatedHeaderIndex.Entry> admitted = new ArrayList<>();
        for (Item item : batch) {
            Objects.requireNonNull(item);
            admitted.add(index.admit(item.candidate(), item.raw(), verifier));
        }
        return List.copyOf(admitted);
    }
}

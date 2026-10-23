package ru.bitcoin.node.rvn.consensus;

import java.util.List;
import java.util.Objects;

/**
 * Single entry point for atomic admission and outcome accounting.
 * Does not invent a proof verifier; production must supply the real one.
 */
public final class RvnHeaderAdmissionService {
    private final RvnValidatedHeaderIndex index;
    private final RvnHeaderFormatPolicy policy;
    private final RvnHeaderProofGate.Verifier verifier;
    private final RvnHeaderAdmissionMetrics metrics;

    public RvnHeaderAdmissionService(RvnValidatedHeaderIndex index,
                                     RvnHeaderFormatPolicy policy,
                                     RvnHeaderProofGate.Verifier verifier,
                                     RvnHeaderAdmissionMetrics metrics) {
        this.index = Objects.requireNonNull(index);
        this.policy = Objects.requireNonNull(policy);
        this.verifier = Objects.requireNonNull(verifier);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public List<RvnValidatedHeaderIndex.Entry> accept(List<RvnHeaderBatchAdmission.Item> batch) {
        try {
            var entries = index.admitBatchAtomic(batch, verifier, policy);
            metrics.accepted(entries.size());
            return entries;
        } catch (RuntimeException e) {
            metrics.rejected();
            throw e;
        }
    }
}

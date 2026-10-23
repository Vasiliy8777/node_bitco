package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Uses a staged-aware verifier factory so every candidate sees preceding batch headers.
 */
public final class RvnContextualAdmissionService {
    private final RvnValidatedHeaderIndex index;
    private final RvnHeaderFormatPolicy format;
    private final RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory;
    private final RvnHeaderAdmissionMetrics metrics;

    public RvnContextualAdmissionService(RvnValidatedHeaderIndex index,
                                         RvnHeaderFormatPolicy format,
                                         RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory,
                                         RvnHeaderAdmissionMetrics metrics) {
        this.index = Objects.requireNonNull(index);
        this.format = Objects.requireNonNull(format);
        this.verifierFactory = Objects.requireNonNull(verifierFactory);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public List<RvnValidatedHeaderIndex.Entry> accept(List<RvnHeaderBatchAdmission.Item> batch) {
        try {
            var entries = index.admitBatchAtomicContextual(batch, verifierFactory, format);
            metrics.accepted(entries.size());
            return entries;
        } catch (RuntimeException failure) {
            metrics.rejected();
            throw failure;
        }
    }
}

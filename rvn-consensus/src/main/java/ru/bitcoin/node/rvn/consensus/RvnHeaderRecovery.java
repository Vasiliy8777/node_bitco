package ru.bitcoin.node.rvn.consensus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Restore into a NEW index. Never expose a partially restored index.
 * Disk bytes are not authenticated and require full consensus verification.
 */
public final class RvnHeaderRecovery {
    private RvnHeaderRecovery() {
    }

    public record Result(RvnValidatedHeaderIndex index, int restored) {
        public Result {
            Objects.requireNonNull(index);
        }
    }

    public static Result recover(Path path,
                                 Supplier<RvnValidatedHeaderIndex> emptyIndexFactory,
                                 RvnHeaderFormatPolicy policy,
                                 RvnHeaderProofGate.Verifier verifier) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(emptyIndexFactory);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(verifier);
        RvnValidatedHeaderIndex candidate = Objects.requireNonNull(emptyIndexFactory.get());
        if (candidate.size() != 0)
            throw new IllegalArgumentException("Factory must return empty index");
        int count = RvnVerifiedHeaderJournal.replay(path, candidate, policy, verifier);
        return new Result(candidate, count);
    }


    /**
     * Strict recovery: all journal records are parsed and checked before any
     * recovered index can be returned to the caller.
     */
    public static Result recoverAudited(Path path,
                                         Supplier<RvnValidatedHeaderIndex> emptyIndexFactory,
                                         RvnHeaderFormatPolicy policy,
                                         RvnHeaderProofGate.Verifier verifier,
                                         RvnHash256 anchor, long anchorHeight,
                                         java.math.BigInteger anchorWork,
                                         java.math.BigInteger powLimit) throws IOException {
        Result result = recover(path, emptyIndexFactory, policy, verifier);
        RvnVerifiedChainAudit.check(result.index(), anchor, anchorHeight, anchorWork, powLimit);
        return result;
    }


    /**
     * Rebuilds an index using a verifier bound to the STAGED state.
     * Journal records remain untrusted. No partially recovered index escapes.
     * The 2000-item batches are processed on a private, newly created index.
     */
    public static Result recoverContextual(
            Path path,
            Supplier<RvnValidatedHeaderIndex> emptyIndexFactory,
            RvnHeaderFormatPolicy policy,
            RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory)
            throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(emptyIndexFactory);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(verifierFactory);
        var index = Objects.requireNonNull(emptyIndexFactory.get());
        if (index.size() != 0)
            throw new IllegalArgumentException("Factory must return an empty index");
        var records = RvnVerifiedHeaderJournal.read(path);
        int restored = 0;
        for (int start = 0; start < records.size(); start += 2000) {
            int end = Math.min(start + 2000, records.size());
            var batch = new java.util.ArrayList<RvnHeaderBatchAdmission.Item>(end - start);
            for (int i = start; i < end; i++) {
                var record = records.get(i);
                var candidate = RvnHeaderCandidateFactory.unverified(
                        record.claimedId(), record.raw(), policy);
                batch.add(new RvnHeaderBatchAdmission.Item(candidate, record.raw()));
            }
            index.admitBatchAtomicContextual(batch, verifierFactory, policy);
            restored += batch.size();
        }
        return new Result(index, restored);
    }

    public static Result recoverContextualAudited(
            Path path,
            Supplier<RvnValidatedHeaderIndex> emptyIndexFactory,
            RvnHeaderFormatPolicy policy,
            RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory,
            RvnHash256 anchor, long anchorHeight,
            java.math.BigInteger anchorWork, java.math.BigInteger powLimit)
            throws IOException {
        var result = recoverContextual(path, emptyIndexFactory, policy, verifierFactory);
        RvnVerifiedChainAudit.check(result.index(), anchor, anchorHeight, anchorWork, powLimit);
        return result;
    }

    public static void save(Path path, RvnValidatedHeaderIndex index) throws IOException {
        Objects.requireNonNull(index);
        RvnVerifiedHeaderJournal.writeAtomically(path, index.journalRecords());
    }
}

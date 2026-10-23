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

        // A journal is a parent-first GRAPH, not necessarily a single chain.
        // Split it into contiguous runs: the normal network headers path can
        // validate each run atomically, while fork boundaries start new runs.
        // Never assume that two consecutive journal records share a parent.
        int restored = 0;
        java.util.Set<RvnHash256> seen = new java.util.HashSet<>();
        java.util.ArrayList<RvnHeaderBatchAdmission.Item> run = new java.util.ArrayList<>(2000);
        RvnHash256 previous = null;
        for (var record : records) {
            if (!seen.add(record.claimedId()))
                throw new SecurityException("Duplicate block ID in journal");
            var candidate = RvnHeaderCandidateFactory.unverified(
                    record.claimedId(), record.raw(), policy);
            var item = new RvnHeaderBatchAdmission.Item(candidate, record.raw());
            if (!run.isEmpty() && (run.size() == 2000 || !candidate.parentId().equals(previous))) {
                index.admitBatchAtomicContextual(run, verifierFactory, policy);
                restored += run.size();
                run.clear();
            }
            run.add(item);
            previous = candidate.id();
        }
        if (!run.isEmpty()) {
            index.admitBatchAtomicContextual(run, verifierFactory, policy);
            restored += run.size();
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

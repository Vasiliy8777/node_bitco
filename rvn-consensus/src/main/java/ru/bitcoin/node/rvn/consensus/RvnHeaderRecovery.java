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

    public static void save(Path path, RvnValidatedHeaderIndex index) throws IOException {
        Objects.requireNonNull(index);
        RvnVerifiedHeaderJournal.writeAtomically(path, index.journalRecords());
    }
}

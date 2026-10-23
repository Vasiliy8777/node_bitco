package ru.bitcoin.node.rvn.consensus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * High-level restart boundary: revalidate untrusted journal against trusted
 * checkpoint, then audit before exposing the recovered index.
 */
public final class RvnHeaderRecoveryManager {
    private final RvnHeaderCheckpoint checkpoint;
    private final RvnHeaderFormatPolicy format;
    private final RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory;
    private final int capacity;

    public RvnHeaderRecoveryManager(RvnHeaderCheckpoint checkpoint,
                                    RvnHeaderFormatPolicy format,
                                    RvnValidatedHeaderIndex.ContextualVerifierFactory verifierFactory,
                                    int capacity) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.format = Objects.requireNonNull(format);
        this.verifierFactory = Objects.requireNonNull(verifierFactory);
        if (capacity < 1 || capacity > 1_000_000)
            throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public RvnHeaderRecovery.Result restore(Path journal) throws IOException {
        return RvnHeaderRecovery.recoverContextualAudited(
                journal, () -> checkpoint.newIndex(capacity), format, verifierFactory,
                checkpoint.blockId(), checkpoint.height(), checkpoint.cumulativeWork(),
                checkpoint.powLimit());
    }

    public void save(Path journal, RvnValidatedHeaderIndex index) throws IOException {
        checkpoint.audit(index);
        RvnHeaderRecovery.save(journal, index);
    }
}

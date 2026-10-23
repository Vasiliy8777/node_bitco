package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Immutable trusted bootstrap metadata. A checkpoint is NOT verified by
 * constructing this object; caller must obtain it from trusted chain parameters.
 */
public record RvnHeaderCheckpoint(RvnHash256 blockId, long height, long timestamp,
                                  BigInteger cumulativeWork, BigInteger powLimit) {
    public RvnHeaderCheckpoint {
        Objects.requireNonNull(blockId);
        Objects.requireNonNull(cumulativeWork);
        Objects.requireNonNull(powLimit);
        if (blockId.isZero() || height < 0 || timestamp < 0
                || timestamp > 0xffff_ffffL || cumulativeWork.signum() < 0
                || powLimit.signum() <= 0 || powLimit.bitLength() > 256)
            throw new IllegalArgumentException("Invalid checkpoint");
    }

    public RvnValidatedHeaderIndex newIndex(int capacity) {
        return new RvnValidatedHeaderIndex(capacity, blockId, height, cumulativeWork, powLimit);
    }

    public RvnVerifiedChainAudit.Summary audit(RvnValidatedHeaderIndex index) {
        return RvnVerifiedChainAudit.check(index, blockId, height, cumulativeWork, powLimit);
    }
}

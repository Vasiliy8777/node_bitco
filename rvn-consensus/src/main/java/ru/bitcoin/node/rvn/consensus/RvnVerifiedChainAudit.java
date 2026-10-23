package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.*;

/**
 * Recomputes height and claimed chainwork consistency of the in-memory verified index.
 */
public final class RvnVerifiedChainAudit {
    private RvnVerifiedChainAudit() {
    }

    public record Summary(int entries, RvnHash256 bestId, long bestHeight, BigInteger bestWork) {
    }

    public static Summary check(RvnValidatedHeaderIndex index, RvnHash256 anchor,
                                long anchorHeight, BigInteger anchorWork, BigInteger powLimit) {
        Objects.requireNonNull(index);
        Objects.requireNonNull(anchor);
        Objects.requireNonNull(anchorWork);
        Objects.requireNonNull(powLimit);
        List<RvnVerifiedHeaderJournal.Record> records = index.journalRecords();
        Map<RvnHash256, RvnValidatedHeaderIndex.Entry> checked = new HashMap<>();
        RvnHash256 best = anchor;
        long height = anchorHeight;
        BigInteger work = anchorWork;
        for (var record : records) {
            var entry = index.find(record.claimedId()).orElseThrow();
            var candidate = entry.candidate();
            new RvnHeaderWireFields(record.raw()).requireMatches(candidate);
            var parent = checked.get(candidate.parentId());
            if (parent == null && !candidate.parentId().equals(anchor))
                throw new SecurityException("Non-parent-first verified index");
            long expectedHeight = Math.addExact(parent == null ? anchorHeight : parent.height(), 1);
            BigInteger expectedBlockWork = RvnBlockProof.fromBits(candidate.bits(), powLimit);
            BigInteger expectedWork = RvnBlockProof.accumulate(
                    parent == null ? anchorWork : parent.chainWork(), expectedBlockWork);
            if (entry.height() != expectedHeight
                    || !entry.blockWork().equals(expectedBlockWork)
                    || !entry.chainWork().equals(expectedWork))
                throw new SecurityException("Inconsistent verified index");
            checked.put(candidate.id(), entry);
            if (expectedWork.compareTo(work) > 0 ||
                    (expectedWork.equals(work) && candidate.id().compareTo(best) < 0)) {
                best = candidate.id();
                height = expectedHeight;
                work = expectedWork;
            }
        }
        if (checked.size() != index.size() || !index.bestId().equals(best)
                || index.bestHeight() != height || !index.bestWork().equals(work))
            throw new SecurityException("Inconsistent verified best tip");
        return new Summary(checked.size(), best, height, work);
    }
}

package ru.bitcoin.node.rvn.consensus;

import java.util.*;

/**
 * Read-only structural preflight before calling the proof gate.
 * Does NOT verify PoW, block ID, difficulty retarget or timestamp consensus.
 */
public final class RvnHeaderBatchPreflight {
    private RvnHeaderBatchPreflight() {
    }

    public static List<RvnHeaderCandidate> check(
            List<RvnHeaderBatchAdmission.Item> batch, RvnHeaderFormatPolicy formatPolicy) {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(formatPolicy);
        if (batch.size() > 2000) throw new IllegalArgumentException("RVN headers batch exceeds 2000");
        List<RvnHeaderCandidate> result = new ArrayList<>(batch.size());
        Set<RvnHash256> ids = new HashSet<>();
        for (int i = 0; i < batch.size(); i++) {
            RvnHeaderBatchAdmission.Item item = Objects.requireNonNull(batch.get(i));
            RvnHeaderCandidate candidate = item.candidate();
            formatPolicy.requireFormat(candidate, item.raw());
            if (candidate.id().equals(candidate.parentId()))
                throw new SecurityException("Self-referential header");
            if (!ids.add(candidate.id()))
                throw new SecurityException("Duplicate block ID within headers batch");
            if (i > 0) {
                RvnHeaderCandidate prev = result.get(i - 1);
                if (!candidate.parentId().equals(prev.id()))
                    throw new SecurityException("Non-contiguous headers batch");
                if (candidate.kawpow() && prev.kawpow()) {
                    if (prev.declaredHeight() == 0xffff_ffffL
                            || candidate.declaredHeight() != prev.declaredHeight() + 1)
                        throw new SecurityException("Nonconsecutive KawPoW declared heights");
                }
            }
            result.add(candidate);
        }
        return List.copyOf(result);
    }
}

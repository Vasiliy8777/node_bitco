package ru.bitcoin.node.chain;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Branch-safe memoization of the expensive canonical-BIP34 ancestry proof.
 * A proven direct child inherits the proof; a fork or height jump must execute
 * the supplied full proof before the cache can move to that branch.
 */
public final class Bip34AncestryCache {
    private ru.bitcoin.node.common.types.Hash256 provenTipHash;
    private long provenTipHeight = -1L;
    private final LongAdder fullProofs = new LongAdder();
    private final LongAdder inheritedProofs = new LongAdder();

    public synchronized boolean prove(BlockIndex candidate, BooleanSupplier fullProof) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fullProof, "fullProof");

        if (provenTipHash != null) {
            if (candidate.hash().equals(provenTipHash) && candidate.height() == provenTipHeight) {
                inheritedProofs.increment();
                return true;
            }
            if (candidate.height() == provenTipHeight + 1L
                    && candidate.previousBlockHash().equals(provenTipHash)) {
                provenTipHash = candidate.hash();
                provenTipHeight = candidate.height();
                inheritedProofs.increment();
                return true;
            }
        }

        fullProofs.increment();
        boolean proven = fullProof.getAsBoolean();
        if (proven) {
            provenTipHash = candidate.hash();
            provenTipHeight = candidate.height();
        }
        return proven;
    }

    public DiagnosticSnapshot diagnosticSnapshot() {
        return new DiagnosticSnapshot(fullProofs.sum(), inheritedProofs.sum());
    }

    public record DiagnosticSnapshot(long fullProofs, long inheritedProofs) {
        public DiagnosticSnapshot minus(DiagnosticSnapshot baseline) {
            Objects.requireNonNull(baseline, "baseline");
            return new DiagnosticSnapshot(
                    fullProofs - baseline.fullProofs,
                    inheritedProofs - baseline.inheritedProofs);
        }
    }
}

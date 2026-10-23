package ru.bitcoin.node.rvn.consensus;

import java.util.Objects;

/**
 * Constructs candidate metadata from the wire payload, except the ID.
 * The supplied ID is UNVERIFIED until a complete PoW/block-hash verifier runs.
 */
public final class RvnHeaderCandidateFactory {
    private RvnHeaderCandidateFactory() {
    }

    public static RvnHeaderCandidate unverified(RvnHash256 claimedId, byte[] raw,
                                                RvnHeaderFormatPolicy formatPolicy) {
        Objects.requireNonNull(claimedId);
        Objects.requireNonNull(formatPolicy);
        formatPolicy.requireFormat(raw);
        RvnHeaderWireFields fields = new RvnHeaderWireFields(raw);
        return new RvnHeaderCandidate(claimedId, fields.parentId(), fields.bits(),
                fields.timestamp(), fields.declaredHeight(), fields.kawpow());
    }
}

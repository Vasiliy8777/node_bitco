package ru.bitcoin.node.rvn.pow;

import ru.bitcoin.node.rvn.consensus.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Consensus gate for hash/target checks; expected nBits and MTP must additionally
 * be supplied through the existing RvnHeaderContextVerifierFactory.
 */
public final class RvnPowConsensusVerifier
        implements RvnHeaderProofVerifierAdapter.BlockIdChecker,
        RvnHeaderProofVerifierAdapter.PowChecker {
    private final RvnPowRouter router;
    private final RvnValidatedHeaderIndex index;
    private final RvnHash256 anchor;
    private final long anchorHeight;
    private final BigInteger powLimit;
    private final RvnPowDigest.ByteOrder digestOrder;

    public RvnPowConsensusVerifier(RvnPowRouter router, RvnValidatedHeaderIndex index,
                                   RvnHash256 anchor, long anchorHeight, BigInteger powLimit,
                                   RvnPowDigest.ByteOrder digestOrder) {
        this.router = Objects.requireNonNull(router);
        this.index = Objects.requireNonNull(index);
        this.anchor = Objects.requireNonNull(anchor);
        if (anchorHeight < 0) throw new IllegalArgumentException("anchorHeight");
        this.anchorHeight = anchorHeight;
        this.powLimit = Objects.requireNonNull(powLimit);
        this.digestOrder = Objects.requireNonNull(digestOrder);
    }

    private long height(RvnHeaderCandidate c) {
        if (c.parentId().equals(anchor)) return Math.addExact(anchorHeight, 1);
        return Math.addExact(index.find(c.parentId())
                .orElseThrow(() -> new SecurityException("Unknown parent")).height(), 1);
    }

    private RvnPowResult checked(RvnHeaderCandidate c, byte[] raw) throws RvnPowException {
        long height = height(c);
        new RvnHeaderWireFields(raw).requireMatches(c);
        return router.calculate(height, raw);
    }

    @Override
    public boolean matches(RvnHeaderCandidate c, byte[] raw) {
        try {
            return Arrays.equals(checked(c, raw).blockIdWire(), c.id().wire());
        } catch (RvnPowException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    @Override
    public boolean valid(RvnHeaderCandidate c, byte[] raw) {
        try {
            return RvnPowDigest.meetsTarget(checked(c, raw).powDigestWire(),
                    digestOrder, c.bits(), powLimit);
        } catch (RvnPowException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }
}

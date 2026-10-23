package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Fail-closed integration boundary. A real Ravencoin PoW and difficulty engine
 * MUST implement all checks; until then this gate cannot authorize admission.
 */
public final class RvnHeaderProofGate {
    private RvnHeaderProofGate() {
    }

    public interface Verifier {
        /**
         * True only if the calculated block ID equals the candidate ID.
         */
        boolean blockIdMatches(RvnHeaderCandidate candidate, byte[] rawHeader);

        /**
         * True only if the network-specific PoW algorithm accepts this header.
         */
        boolean validProofOfWork(RvnHeaderCandidate candidate, byte[] rawHeader);

        /**
         * True only if expected difficulty, deployment and timestamp rules pass.
         */
        boolean validContext(RvnHeaderCandidate candidate);
    }

    public static void requireVerified(RvnHeaderCandidate candidate, byte[] rawHeader, Verifier verifier) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(rawHeader);
        Objects.requireNonNull(verifier);
        if (rawHeader.length != (candidate.kawpow() ? 120 : 80))
            throw new IllegalArgumentException("wrong raw header length");
        if (!verifier.blockIdMatches(candidate, rawHeader) || !verifier.validProofOfWork(candidate, rawHeader)
                || !verifier.validContext(candidate)) throw new SecurityException("unverified Ravencoin header");
    }

    /**
     * Deliberate default: never treat unverified headers as validated.
     */
    public static Verifier unavailable() {
        return new Verifier() {
            public boolean blockIdMatches(RvnHeaderCandidate c, byte[] b) {
                return false;
            }

            public boolean validProofOfWork(RvnHeaderCandidate c, byte[] b) {
                return false;
            }

            public boolean validContext(RvnHeaderCandidate c) {
                return false;
            }
        };
    }
}

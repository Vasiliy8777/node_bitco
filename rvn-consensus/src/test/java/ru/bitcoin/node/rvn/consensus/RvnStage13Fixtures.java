package ru.bitcoin.node.rvn.consensus;

import java.math.BigInteger;

final class RvnStage13Fixtures {
    static final long BITS = 0x207fffffL;

    static RvnHash256 id(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    static byte[] raw(RvnHash256 parent, int height) {
        byte[] b = new byte[120];
        System.arraycopy(parent.wire(), 0, b, 4, 32);
        for (int i = 0; i < 4; i++) {
            b[72 + i] = (byte) (BITS >>> (8 * i));
            b[76 + i] = (byte) (height >>> (8 * i));
        }
        return b;
    }

    static RvnHeaderBatchAdmission.Item item(int id, RvnHash256 parent, int height) {
        byte[] raw = raw(parent, height);
        return new RvnHeaderBatchAdmission.Item(
                RvnHeaderCandidateFactory.unverified(id(id), raw, new RvnHeaderFormatPolicy(0)), raw);
    }

    static RvnValidatedHeaderIndex index(int capacity) {
        return new RvnValidatedHeaderIndex(capacity, id(90), 0, BigInteger.ZERO, CompactTarget.decode(BITS));
    }

    static final RvnHeaderProofGate.Verifier TEST_ONLY = new RvnHeaderProofGate.Verifier() {
        public boolean blockIdMatches(RvnHeaderCandidate c, byte[] b) {
            return true;
        }

        public boolean validProofOfWork(RvnHeaderCandidate c, byte[] b) {
            return true;
        }

        public boolean validContext(RvnHeaderCandidate c) {
            return true;
        }
    };
}

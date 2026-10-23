package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages16to19Test {
    static final long BITS = 0x207fffffL;

    static RvnHash256 id(int n) {
        byte[] a = new byte[32];
        a[0] = (byte) n;
        return new RvnHash256(a);
    }

    static byte[] raw(RvnHash256 parent, long time, int height) {
        byte[] b = new byte[120];
        System.arraycopy(parent.wire(), 0, b, 4, 32);
        for (int i = 0; i < 4; i++) {
            b[68 + i] = (byte) (time >>> (8 * i));
            b[72 + i] = (byte) (BITS >>> (8 * i));
            b[76 + i] = (byte) (height >>> (8 * i));
        }
        return b;
    }

    static RvnHeaderCandidate candidate(int n, RvnHash256 parent, long time, int height) {
        return RvnHeaderCandidateFactory.unverified(id(n), raw(parent, time, height), new RvnHeaderFormatPolicy(0));
    }

    static RvnValidatedHeaderIndex index() {
        return new RvnValidatedHeaderIndex(50, id(90), 0, BigInteger.ZERO, CompactTarget.decode(BITS));
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

    @Test
    void medianAndTimestampBoundary() {
        var index = index();
        for (int i = 1; i <= 11; i++) {
            var parent = i == 1 ? id(90) : id(i - 1);
            index.admit(candidate(i, parent, 100 + i, i), raw(parent, 100 + i, i), TEST_ONLY);
        }
        assertEquals(106, RvnHeaderTimeRules.medianTimePast(index, id(11), id(90), 100));
        assertDoesNotThrow(() -> RvnHeaderTimeRules.requireTimestamp(107, 106, 107, 7200));
        assertThrows(SecurityException.class, () -> RvnHeaderTimeRules.requireTimestamp(106, 106, 107, 7200));
        assertThrows(SecurityException.class, () -> RvnHeaderTimeRules.requireTimestamp(7308, 106, 107, 7200));
        assertEquals(100, RvnHeaderTimeRules.medianTimePast(index, id(90), id(90), 100));
    }

    @Test
    void contextChecksDifficultyAndTime() {
        var index = index();
        var context = new RvnHeaderContextPolicy(index, id(90), 100, 7200, () -> 200L, (c, idx) -> BITS);
        context.requireValid(candidate(1, id(90), 101, 1));
        assertThrows(SecurityException.class, () -> context.requireValid(candidate(1, id(90), 100, 1)));
        var mismatch = new RvnHeaderContextPolicy(index, id(90), 100, 7200, () -> 200L, (c, idx) -> 1L);
        assertThrows(SecurityException.class, () -> mismatch.requireValid(candidate(1, id(90), 101, 1)));
    }

    @Test
    void composedGateAndAudit() {
        var index = index();
        var context = new RvnHeaderContextPolicy(index, id(90), 100, 7200, () -> 200L, (c, idx) -> BITS);
        var adapter = new RvnHeaderProofVerifierAdapter((c, b) -> c.id().equals(id(1)), (c, b) -> true, context);
        var c = candidate(1, id(90), 101, 1);
        index.admit(c, raw(id(90), 101, 1), adapter);
        var audit = RvnVerifiedChainAudit.check(index, id(90), 0, BigInteger.ZERO, CompactTarget.decode(BITS));
        assertEquals(1, audit.entries());
        assertEquals(id(1), audit.bestId());
        assertThrows(SecurityException.class, () -> index.admit(candidate(2, id(1), 102, 2), raw(id(1), 102, 2), adapter));
        assertEquals(1, index.size());
    }
}

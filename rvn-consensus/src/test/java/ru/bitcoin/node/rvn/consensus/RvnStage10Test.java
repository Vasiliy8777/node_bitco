package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnStage10Test {
    static RvnHash256 hash(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    static final long BITS = 0x207fffffL;

    static byte[] wire(RvnHash256 parent, int height) {
        byte[] b = new byte[120];
        System.arraycopy(parent.wire(), 0, b, 4, 32);
        b[72] = (byte) 0xff;
        b[73] = (byte) 0xff;
        b[74] = (byte) 0x7f;
        b[75] = 0x20;
        b[76] = (byte) height;
        return b;
    }

    static RvnHeaderCandidate candidate(int id, RvnHash256 parent, int height) {
        return new RvnHeaderCandidate(hash(id), parent, BITS, 0, height, true);
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

    static RvnValidatedHeaderIndex index() {
        return new RvnValidatedHeaderIndex(20, hash(99), 0, BigInteger.ZERO, CompactTarget.decode(BITS));
    }

    @Test
    void branchTransitionsAndLocator() {
        var idx = index();
        idx.admit(candidate(1, hash(99), 1), wire(hash(99), 1), TEST_ONLY);
        idx.admit(candidate(2, hash(1), 2), wire(hash(1), 2), TEST_ONLY);
        idx.admit(candidate(3, hash(1), 2), wire(hash(1), 2), TEST_ONLY);
        idx.admit(candidate(4, hash(3), 3), wire(hash(3), 3), TEST_ONLY);
        var transition = RvnChainTransition.plan(idx, hash(2), hash(4));
        assertEquals(hash(1), transition.ancestor());
        assertEquals(List.of(hash(2)), transition.disconnect().stream().map(e -> e.candidate().id()).toList());
        assertEquals(List.of(hash(3), hash(4)), transition.connect().stream().map(e -> e.candidate().id()).toList());
        var locator = RvnHeaderLocatorPlan.build(idx, hash(4), hash(99), 20);
        assertArrayEquals(hash(4).wire(), locator.get(0));
        assertArrayEquals(hash(99).wire(), locator.get(locator.size() - 1));
    }

    @Test
    void duplicateDifferentWireRejected() {
        var idx = index();
        var c = candidate(1, hash(99), 1);
        byte[] raw = wire(hash(99), 1);
        idx.admit(c, raw, TEST_ONLY);
        raw[40] = 1;
        assertThrows(SecurityException.class, () -> idx.admit(c, raw, TEST_ONLY));
        byte[] stored = idx.verifiedRaw(c.id()).orElseThrow();
        stored[40] = 3;
        assertEquals(0, idx.verifiedRaw(c.id()).orElseThrow()[40]);
    }

    @Test
    void batchFailsClosed() {
        var idx = index();
        var item = new RvnHeaderBatchAdmission.Item(candidate(1, hash(99), 1), wire(hash(99), 1));
        assertThrows(SecurityException.class, () -> RvnHeaderBatchAdmission.admit(
                idx, List.of(item), RvnHeaderProofGate.unavailable()));
        assertEquals(0, idx.size());
        assertEquals(1, RvnHeaderBatchAdmission.admit(idx, List.of(item), TEST_ONLY).size());
    }
}

package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnContextualAtomicTest {
    static final long BITS = 0x207fffffL;

    static RvnHash256 id(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    static RvnHeaderBatchAdmission.Item item(int id, RvnHash256 parent, int height, long timestamp) {
        byte[] raw = new byte[120];
        System.arraycopy(parent.wire(), 0, raw, 4, 32);
        for (int i = 0; i < 4; i++) {
            raw[68 + i] = (byte) (timestamp >>> (8 * i));
            raw[72 + i] = (byte) (BITS >>> (8 * i));
            raw[76 + i] = (byte) (height >>> (8 * i));
        }
        return new RvnHeaderBatchAdmission.Item(
                RvnHeaderCandidateFactory.unverified(id(id), raw, new RvnHeaderFormatPolicy(0)), raw);
    }

    static RvnValidatedHeaderIndex index() {
        return new RvnValidatedHeaderIndex(20, id(90), 0,
                BigInteger.ZERO, CompactTarget.decode(BITS));
    }

    static RvnHeaderContextVerifierFactory factory() {
        // TEST_ONLY checkers: not a real block-ID or PoW implementation.
        return new RvnHeaderContextVerifierFactory((c, b) -> true, (c, b) -> true,
                (c, i) -> BITS, id(90), 100, 7200, () -> 10000);
    }

    @Test
    void secondHeaderSeesStagedParent() {
        var idx = index();
        var metrics = new RvnHeaderAdmissionMetrics();
        var service = new RvnContextualAdmissionService(idx, new RvnHeaderFormatPolicy(0), factory(), metrics);
        assertEquals(2, service.accept(List.of(item(1, id(90), 1, 101), item(2, id(1), 2, 102))).size());
        assertEquals(2, idx.size());
        assertEquals(id(2), idx.bestId());
        assertEquals(2, metrics.snapshot().acceptedHeaders());
    }

    @Test
    void lateContextFailureRollsBackEntireBatch() {
        var idx = index();
        var metrics = new RvnHeaderAdmissionMetrics();
        var service = new RvnContextualAdmissionService(idx, new RvnHeaderFormatPolicy(0), factory(), metrics);
        assertThrows(SecurityException.class, () -> service.accept(List.of(
                item(1, id(90), 1, 101), item(2, id(1), 2, 100))));
        assertEquals(0, idx.size());
        assertEquals(id(90), idx.bestId());
        assertEquals(1, metrics.snapshot().rejectedBatches());
    }

    @Test
    void missingDifficultyRuleFailsClosed() {
        var idx = index();
        var factory = new RvnHeaderContextVerifierFactory((c, b) -> true, (c, b) -> true,
                (c, i) -> 0x1d00ffffL, id(90), 100, 7200, () -> 10000);
        assertThrows(SecurityException.class, () -> idx.admitBatchAtomicContextual(
                List.of(item(1, id(90), 1, 101)), factory, new RvnHeaderFormatPolicy(0)));
        assertEquals(0, idx.size());
    }
}

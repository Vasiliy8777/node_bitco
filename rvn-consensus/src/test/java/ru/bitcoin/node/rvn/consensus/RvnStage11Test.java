package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RvnStage11Test {
    static final long BITS = 0x207fffffL;

    static RvnHash256 hash(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    static byte[] wire(RvnHash256 parent, long timestamp, int height, boolean kawpow) {
        byte[] raw = new byte[kawpow ? 120 : 80];
        System.arraycopy(parent.wire(), 0, raw, 4, 32);
        for (int i = 0; i < 4; i++) {
            raw[68 + i] = (byte) (timestamp >>> (8 * i));
            raw[72 + i] = (byte) (BITS >>> (8 * i));
            if (kawpow) raw[76 + i] = (byte) (height >>> (8 * i));
        }
        return raw;
    }

    static RvnHeaderBatchAdmission.Item item(int id, RvnHash256 parent,
                                             long time, int height, boolean kawpow,
                                             RvnHeaderFormatPolicy policy) {
        byte[] raw = wire(parent, time, height, kawpow);
        return new RvnHeaderBatchAdmission.Item(
                RvnHeaderCandidateFactory.unverified(hash(id), raw, policy), raw);
    }

    @Test
    void policyChecksBoundaryAndFactory() {
        var policy = new RvnHeaderFormatPolicy(100);
        var legacy = item(1, hash(90), 99, 0, false, policy);
        var kawpow = item(2, hash(1), 100, 2, true, policy);
        assertFalse(legacy.candidate().kawpow());
        assertEquals(-1, legacy.candidate().declaredHeight());
        assertTrue(kawpow.candidate().kawpow());
        assertEquals(2, kawpow.candidate().declaredHeight());
        assertThrows(SecurityException.class, () -> policy.requireFormat(wire(hash(90), 100, 0, false)));
        assertThrows(SecurityException.class, () -> policy.requireFormat(wire(hash(90), 99, 0, true)));
        assertThrows(IllegalArgumentException.class, () -> new RvnHeaderFormatPolicy(-1));
    }

    @Test
    void preflightChecksContinuityAndDuplicates() {
        var policy = new RvnHeaderFormatPolicy(100);
        var first = item(1, hash(90), 100, 1, true, policy);
        var second = item(2, hash(1), 101, 2, true, policy);
        assertEquals(2, RvnHeaderBatchPreflight.check(List.of(first, second), policy).size());
        assertThrows(SecurityException.class, () -> RvnHeaderBatchPreflight.check(
                List.of(first, item(3, hash(90), 101, 2, true, policy)), policy));
        assertThrows(SecurityException.class, () -> RvnHeaderBatchPreflight.check(
                List.of(first, first), policy));
        assertThrows(SecurityException.class, () -> RvnHeaderBatchPreflight.check(
                List.of(first, item(2, hash(1), 101, 3, true, policy)), policy));
    }

    @Test
    void structuralFailureDoesNotPartiallyAdmit() {
        var policy = new RvnHeaderFormatPolicy(100);
        var index = new RvnValidatedHeaderIndex(10, hash(90), 0,
                BigInteger.ZERO, CompactTarget.decode(BITS));
        var first = item(1, hash(90), 100, 1, true, policy);
        var bad = item(2, hash(90), 101, 2, true, policy);
        assertThrows(SecurityException.class, () -> RvnHeaderBatchAdmission.admitPreflighted(
                index, List.of(first, bad), RvnHeaderProofGate.unavailable(), policy));
        assertEquals(0, index.size());
        assertThrows(SecurityException.class, () -> RvnHeaderBatchAdmission.admitPreflighted(
                index, List.of(first), RvnHeaderProofGate.unavailable(), policy));
        assertEquals(0, index.size());
    }
}

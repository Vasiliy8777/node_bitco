package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class NegativeModifiedFeeAdmissionTest {
    @Test void negativeModifiedFeeIsPolicyRejectionEvenWithZeroRelayRate() {
        for (long rate : new long[]{0, 100}) {
            var error = assertThrows(MempoolAdmissionException.class,
                    () -> new MempoolPolicy(new FeeRate(rate)).validateFee(-1, 400));
            assertTrue(error.getMessage().startsWith("Minimum relay fee not met"));
        }
    }
    @Test void negativePriorityProducesNonMutatingSingleProbeRejection() {
        var pool = new Mempool(); var tx = tx(List.of(FUND), 2, 99000);
        pool.prioritise(tx.txId(), -1001); var before = pool.snapshot();
        var probe = pool.testAccept(List.of(tx), CONTEXT, COINS);
        assertFalse(probe.allowed()); assertTrue(probe.rejectReason().startsWith("Minimum relay fee not met"));
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx, CONTEXT, COINS));
        assertEquals(before, pool.snapshot());
        assertEquals(-1001L, pool.prioritisedTransactions().get(tx.txId()));
    }
    @Test void packageProbeRejectsButCpfpSubmissionCanPayForNegativeParent() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 99000);
        var child = tx(List.of(point(parent, 0)), 2, 89000);
        pool.prioritise(parent.txId(), -1001); var before = pool.snapshot();
        var probe = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertFalse(probe.allowed()); assertTrue(probe.rejectReason().startsWith("Minimum relay fee not met"));
        assertEquals(before, pool.snapshot());
        var accepted = pool.admitPackage(List.of(parent, child), CONTEXT, COINS);
        assertEquals(2, accepted.size()); assertEquals(-1, accepted.getFirst().modifiedFee());
        assertEquals(1000, accepted.getFirst().fee());
    }
}

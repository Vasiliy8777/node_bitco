package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class FeeFloorPriorityTest {
    @Test void positiveRollingFloorPrecedesMinrelayForZeroAndNegativeFees() {
        for (long delta : new long[]{0, -1}) {
            var pool = raisedFloor(); var candidate = tx(List.of(FUND), 2, 100000);
            pool.prioritise(candidate.txId(), delta); var before = pool.snapshot(); long floor = pool.minimumFeeRate();
            var probe = pool.testAccept(List.of(candidate), CONTEXT, COINS);
            assertEquals("mempool-min-fee", probe.rejectReason());
            long required = new FeeRate(floor).feeForWeight(
                    ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(candidate));
            assertEquals("mempool min fee not met, " + delta + " < " + required, probe.rejectDetails());
            assertEquals("mempool-min-fee", assertThrows(MempoolAdmissionException.class,
                    () -> pool.admit(candidate, CONTEXT, COINS)).getMessage());
            assertEquals(before, pool.snapshot()); assertEquals(floor, pool.minimumFeeRate());
        }
    }
    @Test void packageProbeUsesSamePositiveFloorPriority() {
        var pool = raisedFloor(); var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 1000); var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertEquals("mempool-min-fee", result.rejectReason());
        assertEquals(0, result.failedTransactionIndex());
        assertTrue(result.rejectDetails().startsWith("mempool min fee not met, 0 < "));
        assertEquals(before, pool.snapshot());
        assertEquals(2, pool.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
    }
    @Test void zeroRollingFloorDoesNotCaptureNegativeFeeRejection() {
        var pool = new Mempool(); var candidate = tx(List.of(FUND), 2, 100000);
        pool.prioritise(candidate.txId(), -1);
        assertEquals(0, pool.minimumFeeRate());
        var probe = pool.testAccept(List.of(candidate), CONTEXT, COINS);
        assertTrue(probe.rejectReason().startsWith("Minimum relay fee not met"));
        long required = new FeeRate(100).feeForWeight(
                ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(candidate));
        assertEquals("min relay fee not met, -1 < " + required, probe.rejectDetails());
        assertTrue(pool.isEmpty());
    }
    @Test void dustFeePrecheckStillPrecedesFeeFloors() {
        var pool = raisedFloor(); var dust = tx(List.of(FUND), 2, 99999, 0);
        assertEquals("dust-with-fee", pool.testAccept(List.of(dust), CONTEXT, COINS).rejectReason());
        assertTrue(pool.isEmpty());
    }
    private static Mempool raisedFloor() {
        var pool = new Mempool(new MempoolPolicy(), new MempoolLimits(64, 101000, 200, 1000, 100), java.time.Clock.systemUTC());
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(tx(List.of(FUND), 2, 1000,1000,1000,1000,1000,1000,1000,1000)));
        assertTrue(pool.isEmpty()); assertTrue(pool.minimumFeeRate() > 10000); return pool;
    }
}

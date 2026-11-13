package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageProbeFeeModeTest {
    @Test void lowFeeChildRetainsFailurePositionAndNumericDetails() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 99000);
        var child = tx(List.of(point(parent, 0)), 2, 99000); var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertFalse(result.allowed()); assertEquals(1, result.failedTransactionIndex());
        assertTrue(result.rejectReason().startsWith("Minimum relay fee not met"));
        assertTrue(result.rejectDetails().startsWith("min relay fee not met, 0 < "));
        assertEquals(before, pool.snapshot());
    }
    @Test void everyProbeEntryMustAlsoPayLiveRollingFloor() {
        var pool = new Mempool(new MempoolPolicy(),
                new MempoolLimits(64, 101000, 2200, 1000, 100), java.time.Clock.systemUTC());
        long[] outputs = new long[32]; Arrays.fill(outputs,1000);
        var evicted = tx(List.of(FUND), 2, outputs);
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(evicted));
        assertTrue(pool.isEmpty());
        long floor = pool.minimumFeeRate();
        assertTrue(floor > 10000);
        var parent = tx(List.of(FUND), 2, 99900);
        var child = tx(List.of(point(parent, 0)), 2, 1000);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertFalse(result.allowed());
        assertEquals("mempool-min-fee", result.rejectReason());
        assertEquals(before, pool.snapshot());
        assertEquals(floor, pool.minimumFeeRate());
        assertEquals(2, pool.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
    }

    @Test void cpfpProbeRejectsWhileSubmissionAccepts() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        var before = pool.snapshot();
        assertFalse(pool.testAccept(List.of(parent, child), CONTEXT, COINS).allowed());
        assertEquals(before, pool.snapshot());
        assertEquals(2, pool.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
    }

    @Test void individuallyPayingProbeSucceedsWithoutMutation() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 99900);
        var child = tx(List.of(point(parent, 0)), 2, 99800);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertTrue(result.allowed());
        assertEquals(List.of(100L, 100L), result.entries().stream().map(MempoolEntry::fee).toList());
        assertEquals(before, pool.snapshot());
    }

    @Test void lowFeeChildCannotBorrowFromHighFeeParentInProbe() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 90000);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        var before = pool.snapshot();
        assertFalse(pool.testAccept(List.of(parent, child), CONTEXT, COINS).allowed());
        assertEquals(before, pool.snapshot());
    }

    @Test void feeDeltaHelpsOnlyTheTransactionToWhichItApplies() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        pool.prioritise(child.txId(), 10000);
        assertFalse(pool.testAccept(List.of(parent, child), CONTEXT, COINS).allowed());
        pool.prioritise(parent.txId(), 1000);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertTrue(result.allowed());
        assertEquals(0, result.entries().getFirst().fee());
        assertEquals(1000, result.entries().getFirst().modifiedFee());
        assertEquals(before, pool.snapshot());
    }
}

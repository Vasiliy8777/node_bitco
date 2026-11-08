package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageSharedPolicyPhaseTest {
    private static final OutPoint ABSENT = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
    @Test void laterMissingInputPrecedesInPackageTrucTopology() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 3, 99000);
        var child = tx(List.of(point(parent, 0)), 3, 98000);
        var grandchild = tx(List.of(point(child, 0)), 3, 97000);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child, grandchild, tx(List.of(ABSENT), 2, 1000)), CONTEXT, COINS);
        assertEquals("missing-inputs", result.rejectReason()); assertEquals(3, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
    }
    @Test void inPackageTrucFailureIsPackageWideBeforeScripts() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 3, 99000);
        var child = tx(List.of(point(parent, 0)), 3, 98000);
        var grandchild = tx(List.of(point(child, 0)), 3, 97000);
        var result = pool.testAccept(List.of(parent, child, grandchild), CONTEXT, COINS);
        assertEquals("TRUC-violation", result.rejectReason()); assertTrue(result.packagePolicyFailure());
        assertTrue(pool.isEmpty());
    }
    @Test void laterMissingInputPrecedesSharedClusterLimit() {
        var pool = smallPool(); var parent = tx(List.of(FUND), 2, 99000);
        pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        var child = tx(List.of(point(parent, 0)), 2, 98000);
        var result = pool.testAccept(List.of(child, tx(List.of(ABSENT), 2, 1000)), CONTEXT, COINS);
        assertEquals("missing-inputs", result.rejectReason()); assertEquals(1, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
    }
    @Test void clusterLimitIsPackageWideAfterSuccessfulPrechecks() {
        var pool = smallPool(); var parent = tx(List.of(FUND), 2, 99000);
        pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        UtxoView coins = out -> out.equals(ABSENT) ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(out);
        var result = pool.testAccept(List.of(tx(List.of(point(parent, 0)), 2, 98000), tx(List.of(ABSENT), 2, 99000)), CONTEXT, coins);
        assertEquals("too-large-cluster", result.rejectReason()); assertTrue(result.packagePolicyFailure());
        assertEquals(before, pool.snapshot());
    }
    @Test void laterMissingInputPrecedesEphemeralSpendFailure() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 100000, 0);
        var validChild = tx(List.of(point(parent, 0), point(parent, 1)), 2, 99000);
        pool.admitPackage(List.of(parent, validChild), CONTEXT, COINS); pool.remove(validChild.txId());
        var before = pool.snapshot(); var missingDust = tx(List.of(point(parent, 0)), 2, 99000);
        var result = pool.testAccept(List.of(missingDust, tx(List.of(ABSENT), 2, 1000)), CONTEXT, COINS);
        assertEquals("missing-inputs", result.rejectReason()); assertEquals(1, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
    }
    private static Mempool smallPool() {
        return new Mempool(new MempoolPolicy(), new MempoolLimits(1, 101000, 1000000, 1000, 100), java.time.Clock.systemUTC());
    }
}

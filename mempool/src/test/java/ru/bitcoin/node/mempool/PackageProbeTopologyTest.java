package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageProbeTopologyTest {
    @Test void submissionPreflightUsesCpfpAndRbfWithoutMutation() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 70000);
        var transactions = List.of(parent, child);
        assertFalse(pool.testAccept(transactions, CONTEXT, COINS).allowed());
        assertTrue(pool.testSubmitPackage(transactions, CONTEXT, COINS).allowed());
        assertTrue(pool.isEmpty());
        var original = tx(List.of(FUND), 2, 90000);
        pool.admit(original, CONTEXT, COINS);
        var before = pool.snapshot();
        assertFalse(pool.testAccept(transactions, CONTEXT, COINS).allowed());
        assertTrue(pool.testSubmitPackage(transactions, CONTEXT, COINS).allowed());
        assertEquals(before, pool.snapshot());
    }

    @Test void threeGenerationChainIsValidProbeButNotSubmission() {
        var parent = tx(List.of(FUND), 2, 99000);
        var child = tx(List.of(point(parent, 0)), 2, 98000);
        var grandchild = tx(List.of(point(child, 0)), 2, 97000);
        checkProbeOnly(List.of(parent, child, grandchild), COINS);
    }

    @Test void disconnectedTransactionsAreValidProbeButNotSubmission() {
        var other = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
        UtxoView coins = out -> out.equals(other) ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(out);
        checkProbeOnly(List.of(tx(List.of(FUND), 2, 99000), tx(List.of(other), 2, 99000)), coins);
    }

    @Test void branchedProbeNeedNotEndInCommonChild() {
        var parent = tx(List.of(FUND), 2, 49000, 49000);
        var left = tx(List.of(point(parent, 0)), 2, 48000);
        var right = tx(List.of(point(parent, 1)), 2, 48000);
        checkProbeOnly(List.of(parent, left, right), COINS);
    }

    @Test void generalProbesStillRejectDuplicatesUnsortedAndDoubleSpends() {
        var parent = tx(List.of(FUND), 2, 99000);
        var child = tx(List.of(point(parent, 0)), 2, 98000);
        var pool = new Mempool();
        for (var packageTxs : List.of(List.of(child, parent), List.of(parent, parent),
                List.of(parent, tx(List.of(FUND), 2, 98000)))) {
            assertFalse(pool.testAccept(packageTxs, CONTEXT, COINS).allowed());
            assertTrue(pool.isEmpty());
        }
    }

    private static void checkProbeOnly(List<Transaction> transactions, UtxoView coins) {
        var pool = new Mempool();
        var before = pool.snapshot();
        var probe = pool.testAccept(transactions, CONTEXT, coins);
        assertTrue(probe.allowed(), probe.rejectReason());
        assertEquals(transactions.stream().map(Transaction::txId).toList(),
                probe.entries().stream().map(e -> e.transaction().txId()).toList());
        assertEquals(before, pool.snapshot());
        assertThrows(MempoolAdmissionException.class, () -> pool.admitPackage(transactions, CONTEXT, coins));
        assertEquals(before, pool.snapshot());
    }
}

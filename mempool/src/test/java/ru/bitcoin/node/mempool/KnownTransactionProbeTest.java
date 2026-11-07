package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class KnownTransactionProbeTest {
    @Test void packageProbeRejectsKnownParentInsteadOfDeduplicatingIt() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        var child = tx(List.of(point(parent, 0)), 2, 80000);
        pool.admit(parent, CONTEXT, COINS);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertEquals("txn-already-in-mempool", result.rejectReason());
        assertEquals(0, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
        assertTrue(pool.testSubmitPackage(List.of(parent, child), CONTEXT, COINS).allowed());
        assertEquals(before, pool.snapshot());
    }
    @Test void failureIndexCanFollowSuccessfulUnrelatedTransaction() {
        var pool = new Mempool(); var known = tx(List.of(FUND), 2, 90000);
        pool.admit(known, CONTEXT, COINS);
        var other = new ru.bitcoin.node.protocol.transaction.OutPoint(
                ru.bitcoin.node.common.types.Hash256.fromDisplayHex("22".repeat(32)), new ru.bitcoin.node.common.types.UInt32(0));
        var fresh = tx(List.of(other), 2, 90000);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(fresh, known), CONTEXT, out -> out.equals(other)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(out));
        assertEquals("txn-already-in-mempool", result.rejectReason());
        assertEquals(1, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
    }
    @Test void exactWitnessReportsAlreadyInMempoolWithoutReadingCoins() {
        var pool = new Mempool(); var tx = tx(List.of(FUND), 2, 90000);
        pool.admit(tx, CONTEXT, COINS);
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(tx), CONTEXT, out -> {throw new AssertionError("Unexpected UTXO read");});
        assertEquals("txn-already-in-mempool", result.rejectReason());
        assertEquals(before, pool.snapshot());
    }
    @Test void sameTxidDifferentWitnessIsDistinguishedBeforeScriptValidation() {
        var pool = new Mempool(); var tx = tx(List.of(FUND), 2, 90000);
        pool.admit(tx, CONTEXT, COINS);
        var input = tx.inputs().getFirst();
        var changed = new Transaction(tx.version(), List.of(new TxIn(input.previousOutput(), input.scriptSig(),
                input.sequence(), new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
        assertEquals(tx.txId(), changed.txId()); assertNotEquals(tx.wtxId(), changed.wtxId());
        var before = pool.snapshot();
        var result = pool.testAccept(List.of(changed), CONTEXT, COINS);
        assertEquals("txn-same-nonwitness-data-in-mempool", result.rejectReason());
        assertEquals(before, pool.snapshot());
        assertEquals(result.rejectReason(), assertThrows(MempoolAdmissionException.class,
                () -> pool.admit(changed, CONTEXT, COINS)).getMessage());
    }
}

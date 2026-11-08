package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageScriptResultsTest {
    @Test void singleProbeReturnsScriptDiagnosticWithoutChangingOrdinaryAdmission() {
        var pool = new Mempool(); var transaction = badWitness(tx(List.of(FUND), 2, 99000));
        var result = pool.testAccept(List.of(transaction), CONTEXT, COINS);
        assertEquals(0, result.failedTransactionIndex());
        assertEquals("mempool-script-verify-flag-failed (Witness program hash mismatch)", result.rejectReason());
        assertThrows(ru.bitcoin.node.consensus.transaction.TransactionValidationException.class,
                () -> pool.admit(transaction, CONTEXT, COINS));
        assertTrue(pool.isEmpty());
    }
    @Test void scriptFailurePreservesOnlySuccessfulPrefixAtEveryPosition() {
        var parent = tx(List.of(FUND), 2, 99000);
        var child = tx(List.of(point(parent, 0)), 2, 98000);
        var grandchild = tx(List.of(point(child, 0)), 2, 97000);
        var original = List.of(parent, child, grandchild);
        for (int position = 0; position < original.size(); position++) {
            var pool = new Mempool(); var pack = new ArrayList<>(original);
            pack.set(position, badWitness(pack.get(position)));
            var before = pool.snapshot();
            var result = pool.testAccept(pack, CONTEXT, COINS);
            assertFalse(result.allowed()); assertFalse(result.packagePolicyFailure());
            assertEquals(position, result.failedTransactionIndex());
            assertEquals(original.subList(0, position).stream().map(Transaction::txId).toList(),
                    result.entries().stream().map(e -> e.transaction().txId()).toList());
            assertEquals("mempool-script-verify-flag-failed (Witness program hash mismatch)", result.rejectReason());
            var failed = pack.get(position); var input = failed.inputs().getFirst().previousOutput();
            assertEquals(result.rejectReason() + ", input 0 of " + failed.txId().toDisplayHex()
                    + " (wtxid " + failed.wtxId().toDisplayHex() + "), spending "
                    + input.transactionId().toDisplayHex() + ":" + input.outputIndex().value(), result.rejectDetails());
            assertEquals(before, pool.snapshot());
        }
    }
    @Test void firstScriptFailureLeavesLaterScriptsUnexecuted() {
        var parent = badWitness(tx(List.of(FUND), 2, 99000));
        var child = badWitness(tx(List.of(point(parent, 0)), 2, 98000));
        var result = new Mempool().testAccept(List.of(parent, child), CONTEXT, COINS);
        assertEquals(0, result.failedTransactionIndex()); assertTrue(result.entries().isEmpty());
    }
    private static Transaction badWitness(Transaction tx) {
        var input = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}

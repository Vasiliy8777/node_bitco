package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackagePrecheckPhaseTest {
    @Test void laterMissingInputPrecedesEarlierInvalidWitness() {
        var pool = new Mempool(); var parent = invalid(tx(List.of(FUND), 2, 99000));
        var absent = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
        var child = tx(List.of(point(parent, 0), absent), 2, 90000); var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertEquals("missing-inputs", result.rejectReason()); assertEquals(1, result.failedTransactionIndex());
        assertEquals(before, pool.snapshot());
    }
    @Test void laterFeeFailurePrecedesEarlierInvalidWitness() {
        var pool = new Mempool(); var parent = invalid(tx(List.of(FUND), 2, 99000));
        var child = tx(List.of(point(parent, 0)), 2, 99000); var before = pool.snapshot();
        var result = pool.testAccept(List.of(parent, child), CONTEXT, COINS);
        assertTrue(result.rejectReason().startsWith("Minimum relay fee not met"));
        assertEquals(1, result.failedTransactionIndex()); assertEquals(before, pool.snapshot());
    }
    @Test void successfulPrechecksDoNotBypassBadScriptAndInputsStayCached() {
        var pool = new Mempool(); var parent = invalid(tx(List.of(FUND), 2, 99000));
        var child = tx(List.of(point(parent, 0)), 2, 98000); var before = pool.snapshot();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ru.bitcoin.node.consensus.transaction.UtxoView coins = out -> {
            if (reads.incrementAndGet() > 1) throw new AssertionError("External inputs reread in script phase");
            return COINS.find(out);
        };
        var result = pool.testAccept(List.of(parent, child), CONTEXT, coins);
        assertEquals(0, result.failedTransactionIndex());
        assertEquals("mempool-script-verify-flag-failed (Witness program hash mismatch)", result.rejectReason());
        assertTrue(result.entries().isEmpty());
        assertEquals(1, reads.get()); assertEquals(before, pool.snapshot());
    }
    private static Transaction invalid(Transaction tx) {
        var input = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}

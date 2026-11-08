package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class SharedSubmissionPhaseTest {
    private static final OutPoint ABSENT = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
    @Test void laterMissingInputPrecedesBadParentScript() {
        var parent = bad(tx(List.of(FUND), 2, 100000));
        var child = tx(List.of(point(parent, 0), ABSENT), 2, 90000);
        var pool = new Mempool();
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertTrue(result.transactions().get(parent.wtxId()).error().startsWith("min relay fee not met"));
        assertEquals("bad-txns-inputs-missingorspent", result.transactions().get(child.wtxId()).error());
        assertTrue(pool.isEmpty());
    }
    @Test void aggregateFeeFailurePrecedesBadParentScript() {
        var parent = bad(tx(List.of(FUND), 2, 100000)); var child = tx(List.of(point(parent, 0)), 2, 100000);
        var pool = new Mempool(); var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        for (var item : result.transactions().values()) assertTrue(item.error().startsWith("min relay fee not met"));
        assertTrue(pool.isEmpty());
    }
    @Test void missingInputPrecedesSharedClusterFailure() {
        var pool = smallPool(); var original = tx(List.of(FUND), 2, 99000); pool.admit(original, CONTEXT, COINS);
        var parent = bad(tx(List.of(point(original, 0)), 2, 99000));
        var child = tx(List.of(point(parent, 0), ABSENT), 2, 98000); var before = pool.snapshot();
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertEquals("bad-txns-inputs-missingorspent", result.transactions().get(child.wtxId()).error());
        assertTrue(result.transactions().get(parent.wtxId()).error().startsWith("min relay fee not met"));
        assertEquals(before, pool.snapshot());
    }
    @Test void clusterFailureIsPackageWideAfterAggregateFeeAndBeforeScripts() {
        var pool = smallPool(); var original = tx(List.of(FUND), 2, 99000); pool.admit(original, CONTEXT, COINS);
        var parent = bad(tx(List.of(point(original, 0)), 2, 99000));
        var child = tx(List.of(point(parent, 0)), 2, 98000); var before = pool.snapshot();
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertEquals("too-large-cluster", result.packageMessage()); assertEquals(before, pool.snapshot());
        assertTrue(result.transactions().get(parent.wtxId()).error().startsWith("min relay fee not met"));
        assertEquals("bad-txns-inputs-missingorspent", result.transactions().get(child.wtxId()).error());
    }
    @Test void successfulRetryUsesPinnedCoinsWithoutReReadingDuringScripts() {
        var parent = tx(List.of(FUND), 2, 100000); var child = tx(List.of(point(parent, 0)), 2, 90000);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ru.bitcoin.node.consensus.transaction.UtxoView coins = out -> {
            if (out.equals(FUND) && reads.incrementAndGet() > 2) throw new AssertionError("External coin reread during scripts");
            return COINS.find(out);
        };
        var pool = new Mempool(); var result = pool.submitPackage(List.of(parent, child), CONTEXT, coins, 0);
        assertEquals("success", result.packageMessage()); assertEquals(2, pool.size()); assertEquals(2, reads.get());
    }
    @Test void ephemeralFailurePrecedesInvalidParentScriptsAndNamesChild() {
        var parent = bad(tx(List.of(FUND), 2, 100000, 0));
        var child = tx(List.of(point(parent, 0)), 2, 90000); var pool = new Mempool();
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertEquals("unspent-dust", result.packageMessage());
        assertTrue(result.transactions().get(parent.wtxId()).error().startsWith("min relay fee not met"));
        assertEquals("missing-ephemeral-spends, tx " + child.txId().toDisplayHex() + " (wtxid="
                + child.wtxId().toDisplayHex() + ") did not spend parent's ephemeral dust", result.transactions().get(child.wtxId()).error());
        assertTrue(pool.isEmpty());
    }
    private static Mempool smallPool() {
        return new Mempool(new MempoolPolicy(), new MempoolLimits(1, 101000, 1000000, 1000, 100), java.time.Clock.systemUTC());
    }
    private static Transaction bad(Transaction tx) {
        var in = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(in.previousOutput(), in.scriptSig(), in.sequence(),
                new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}

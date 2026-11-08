package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PartialPackageSubmissionTest {
    @Test void validParentSurvivesInvalidChild() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 99000);
        var child = bad(tx(List.of(point(parent, 0)), 2, 98000));
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertEquals("transaction failed", result.packageMessage()); assertEquals(1, pool.size());
        assertTrue(pool.contains(parent.txId())); assertFalse(pool.contains(child.txId()));
        var parentResult = result.transactions().get(parent.wtxId());
        assertFalse(parentResult.known()); assertEquals(List.of(parent.wtxId()), parentResult.effectiveIncludes());
        assertTrue(result.transactions().get(child.wtxId()).error().startsWith("mempool-script-verify-flag-failed"));
    }
    @Test void cpfpRetryIncludesOnlyFailedIndividualsAndKnownEntriesDoNotRepeatFees() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 0);
        assertEquals("success", result.packageMessage()); assertEquals(2, pool.size());
        for (var tx : List.of(parent, child)) assertEquals(List.of(parent.wtxId(), child.wtxId()), result.transactions().get(tx.wtxId()).effectiveIncludes());
        var repeated = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 1);
        assertEquals("success", repeated.packageMessage());
        for (var item : repeated.transactions().values()) { assertTrue(item.known()); assertNull(item.effectiveFeeRate()); }
    }
    @Test void clientCapRejectsOnlyNewChildAndPreservesParent() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 99900);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        var result = pool.submitPackage(List.of(parent, child), CONTEXT, COINS, 10000);
        assertEquals("transaction failed", result.packageMessage()); assertTrue(pool.contains(parent.txId()));
        assertEquals("max feerate exceeded", result.transactions().get(child.wtxId()).error());
    }
    @Test void hardFailureDoesNotPreventIndependentParentAcceptance() {
        var secondFund = new OutPoint(ru.bitcoin.node.common.types.Hash256.fromDisplayHex("22".repeat(32)), new ru.bitcoin.node.common.types.UInt32(0));
        ru.bitcoin.node.consensus.transaction.UtxoView coins = point -> point.equals(secondFund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(point);
        var first = bad(tx(List.of(FUND), 2, 99000)); var second = tx(List.of(secondFund), 2, 99000);
        var child = tx(List.of(point(first, 0), point(second, 0)), 2, 190000);
        var pool = new Mempool(); var result = pool.submitPackage(List.of(first, second, child), CONTEXT, coins, 0);
        assertEquals("transaction failed", result.packageMessage()); assertEquals(1, pool.size());
        assertTrue(pool.contains(second.txId())); assertNull(result.transactions().get(second.wtxId()).error());
        assertEquals("bad-txns-inputs-missingorspent", result.transactions().get(child.wtxId()).error());
    }
    @Test void clientLimitUsesTruncatedCoreRateRatherThanCrossMultiplication() {
        var tx = tx(List.of(FUND), 2, 99000); var reference = new Mempool().admit(tx, CONTEXT, COINS);
        long cap = reference.feeRate().satoshisPerKiloByte();
        assertEquals("success", new Mempool().submitPackage(List.of(tx), CONTEXT, COINS, cap).packageMessage());
        assertEquals("max feerate exceeded", new Mempool().submitPackage(List.of(tx), CONTEXT, COINS, cap - 1)
                .transactions().get(tx.wtxId()).error());
    }
    private static Transaction bad(Transaction tx) {
        var in = tx.inputs().getFirst();
        return new Transaction(tx.version(), List.of(new TxIn(in.previousOutput(), in.scriptSig(), in.sequence(), new Witness(List.of(new byte[]{0})))), tx.outputs(), tx.lockTime());
    }
}

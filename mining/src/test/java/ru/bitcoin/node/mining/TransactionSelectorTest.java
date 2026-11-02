package ru.bitcoin.node.mining;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TransactionSelectorTest {
    private static Transaction tx(Hash256 parent, int tag) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(parent, new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }

    private static MempoolEntry entry(Transaction tx, long fee, long ops) {
        return new MempoolEntry(tx, fee, TransactionWeight.calculate(tx), 0, ops);
    }

    @Test void cachedSnapshotsRemainCoherentAcrossFeeChangesAndRemoval() {
        byte[] script = java.util.HexFormat.of().parseHex("0020" + java.util.HexFormat.of().formatHex(
                ru.bitcoin.node.crypto.hash.Sha256.hash(new byte[]{0x51})));
        var fund = new OutPoint(Hash256.fromDisplayHex("71".repeat(32)), new UInt32(0));
        var parent = new Transaction(2, List.of(new TxIn(fund, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))), List.of(new TxOut(90_000, script)), new UInt32(0));
        var child = new Transaction(2, List.of(new TxIn(new OutPoint(parent.txId(), new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))), List.of(new TxOut(80_000, script)), new UInt32(0));
        var pool = new Mempool();
        var context = new MempoolValidationContext(200, 1_700_000_000L, height -> 1_600_000_000L);
        ru.bitcoin.node.consensus.transaction.UtxoView coins = point -> point.equals(fund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100_000, script, 100, false)) : Optional.empty();
        pool.admit(parent, context, coins); pool.admit(child, context, coins);
        var original = pool.miningSnapshot();
        compareSelection(original);
        pool.prioritise(child.txId(), 40_000);
        compareSelection(pool.miningSnapshot());
        assertEquals(0, original.entries().get(1).feeDelta(), "Published snapshots must not change with live prioritisation");
        assertThrows(UnsupportedOperationException.class, () -> original.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.clusters().getFirst().clear());
        pool.remove(parent.txId());
        compareSelection(pool.miningSnapshot());
        assertEquals(List.of(parent, child), TransactionSelector.selectSnapshot(original, 4_000_000, 80_000, new FeeRate(0)));
    }

    private static void compareSelection(Mempool.MiningSnapshot snapshot) {
        for (long weight : new long[]{0, 400, 800, 4_000_000})
            for (long sigops : new long[]{0, 1, 80_000})
                for (long fee : new long[]{0, 100, 100_000})
                    assertEquals(TransactionSelector.select(snapshot.entries(), weight, sigops, new FeeRate(fee)),
                            TransactionSelector.selectSnapshot(snapshot, weight, sigops, new FeeRate(fee)));
    }

    @Test
    void childPaysForParentAndParentsPrecedeChildrenRegardlessOfSnapshotOrder() {
        var parent = tx(Hash256.fromDisplayHex("11".repeat(32)), 1);
        var child = tx(parent.txId(), 2);
        var independent = tx(Hash256.fromDisplayHex("22".repeat(32)), 3);
        var snapshot = List.of(entry(child, 1000, 0), entry(independent, 400, 0), entry(parent, 0, 0));
        long budget = TransactionWeight.calculate(parent) + TransactionWeight.calculate(child);
        assertEquals(List.of(parent, child), TransactionSelector.select(snapshot, budget, 100, new FeeRate(1000)));
        assertEquals(List.of(independent), TransactionSelector.select(snapshot, budget - 1, 100, new FeeRate(1000)));
    }

    @Test
    void sigopsBudgetAndAlreadyChosenParentAreAccountedOnce() {
        var parent = tx(Hash256.fromDisplayHex("11".repeat(32)), 1);
        var child = tx(parent.txId(), 2);
        var snapshot = List.of(entry(child, 100, 2), entry(parent, 1000, 2));
        long budget = TransactionWeight.calculate(parent) + TransactionWeight.calculate(child);
        assertEquals(List.of(parent), TransactionSelector.select(snapshot, budget, 3, new FeeRate(0)));
        assertEquals(List.of(parent, child), TransactionSelector.select(snapshot, budget, 4, new FeeRate(0)));
        assertTrue(TransactionSelector.select(snapshot, 0, 100, new FeeRate(0)).isEmpty());
    }

    @Test
    void clusterChunksCompeteByChunkFeerateAndKeepDependencies() {
        var lowParent = tx(Hash256.fromDisplayHex("31".repeat(32)), 1);
        var highChild = tx(lowParent.txId(), 2);
        var medium = tx(Hash256.fromDisplayHex("32".repeat(32)), 3);
        var p = entry(lowParent, 0, 0);
        var c = entry(highChild, 5000, 0);
        var m = entry(medium, 1500, 0);
        long pairWeight = p.weight() + c.weight();
        var selected = TransactionSelector.select(List.of(c, m, p), pairWeight, 100, new FeeRate(0));
        assertEquals(List.of(lowParent, highChild), selected);
    }


    @Test
    void modifiedFeeChangesMiningOrderWithoutChangingBaseFee() {
        var first = tx(Hash256.fromDisplayHex("41".repeat(32)), 1);
        var second = tx(Hash256.fromDisplayHex("42".repeat(32)), 2);
        var lowBasePrioritised = new MempoolEntry(first, 100, TransactionWeight.calculate(first), 0, 0, 10_000);
        var highBase = new MempoolEntry(second, 1_000, TransactionWeight.calculate(second), 0, 0, 0);
        long budget = lowBasePrioritised.weight();
        assertEquals(100, lowBasePrioritised.fee());
        assertEquals(10_100, lowBasePrioritised.modifiedFee());
        assertEquals(List.of(first), TransactionSelector.select(List.of(highBase, lowBasePrioritised), budget, 100, new FeeRate(0)));
    }
    @Test
    void negativeModifiedFeeIsValidButExcludedByNonNegativeMiningFloor() {
        var penalized = tx(Hash256.fromDisplayHex("51".repeat(32)), 1);
        long weight = TransactionWeight.calculate(penalized);
        var entry = new MempoolEntry(penalized, 1_000, weight, 0, 0, -2_000);
        assertEquals(-1_000, entry.modifiedFee());
        assertDoesNotThrow(() -> ClusterLinearization.mempoolDiagram(Map.of(penalized.txId(), entry)));
        assertTrue(TransactionSelector.select(List.of(entry), weight, 100, new FeeRate(0)).isEmpty());
    }

}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ClusterLinearizationTest {
    private static Transaction tx(List<Hash256> parents, int tag) {
        return new Transaction(2, parents.stream().map(p -> new TxIn(new OutPoint(p, new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)).toList(),
                List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }

    private static MempoolEntry e(Transaction tx, long fee) {
        return new MempoolEntry(tx, fee, TransactionWeight.calculate(tx), 0, 0);
    }

    private static Map<Hash256, MempoolEntry> map(MempoolEntry... es) {
        var m = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var e : es) m.put(e.transaction().txId(), e);
        return m;
    }

    @Test
    void lowFeeParentAndHighFeeChildFormOneChunk() {
        var p = tx(List.of(Hash256.fromDisplayHex("11".repeat(32))), 1);
        var c = tx(List.of(p.txId()), 2);
        var m = map(e(p, 0), e(c, 10_000));
        var chunks = ClusterLinearization.chunks(m, Set.of(p.txId(), c.txId()));
        assertEquals(1, chunks.size());
        assertEquals(List.of(p.txId(), c.txId()), chunks.getFirst().transactions());
    }

    @Test
    void highFeeParentAndLowFeeChildSplitIntoDescendingChunks() {
        var p = tx(List.of(Hash256.fromDisplayHex("11".repeat(32))), 1);
        var c = tx(List.of(p.txId()), 2);
        var m = map(e(p, 10_000), e(c, 0));
        var chunks = ClusterLinearization.chunks(m, Set.of(p.txId(), c.txId()));
        assertEquals(2, chunks.size());
        assertEquals(List.of(p.txId()), chunks.get(0).transactions());
        assertEquals(List.of(c.txId()), chunks.get(1).transactions());
        assertTrue(ClusterLinearization.compareRate(chunks.get(0).fee(), chunks.get(0).virtualSize(), chunks.get(1).fee(), chunks.get(1).virtualSize()) >= 0);
    }

    @Test
    void multiParentDagIsTopologicalAndDeterministic() {
        var a = tx(List.of(Hash256.fromDisplayHex("11".repeat(32))), 1);
        var b = tx(List.of(Hash256.fromDisplayHex("22".repeat(32))), 2);
        var c = tx(List.of(a.txId(), b.txId()), 3);
        var m = map(e(c, 20_000), e(b, 0), e(a, 0));
        var cluster = Set.of(a.txId(), b.txId(), c.txId());
        var one = ClusterLinearization.linearize(m, cluster);
        var two = ClusterLinearization.linearize(m, cluster);
        assertEquals(one, two);
        assertTrue(one.indexOf(a.txId()) < one.indexOf(c.txId()));
        assertTrue(one.indexOf(b.txId()) < one.indexOf(c.txId()));
    }

    @Test
    void diagramComparisonRejectsAnyWorsePrefix() {
        var p = tx(List.of(Hash256.fromDisplayHex("11".repeat(32))), 1);
        var c = tx(List.of(p.txId()), 2);

        /*
         * Keep parent and child as separate descending chunks in both diagrams.
         * The replacement pays more in total, but its first chunk is worse.
         * A fee-rate diagram comparison must reject that regression instead of
         * looking only at aggregate cluster fees.
         */
        var oldMap = map(e(p, 10_000), e(c, 1_000));
        var newMap = map(e(p, 9_000), e(c, 3_000));
        var ids = Set.of(p.txId(), c.txId());

        var oldChunks = ClusterLinearization.chunks(oldMap, ids);
        var newChunks = ClusterLinearization.chunks(newMap, ids);
        assertEquals(2, oldChunks.size());
        assertEquals(2, newChunks.size());
        assertTrue(ClusterLinearization.compareDiagrams(newChunks, oldChunks) < 0);
    }

    @Test
    void diagramComparisonAcceptsCpfpAggregationThatImprovesEveryPrefix() {
        var p = tx(List.of(Hash256.fromDisplayHex("11".repeat(32))), 1);
        var c = tx(List.of(p.txId()), 2);
        var oldMap = map(e(p, 5_000), e(c, 5_000));
        var newMap = map(e(p, 4_000), e(c, 7_000));
        var ids = Set.of(p.txId(), c.txId());

        /*
         * The high-fee child pulls its parent into one package chunk. Although
         * the parent's individual fee is lower, the resulting cumulative
         * diagram is never worse and finishes with a higher total fee.
         */
        assertTrue(ClusterLinearization.compareDiagrams(
                ClusterLinearization.chunks(newMap, ids),
                ClusterLinearization.chunks(oldMap, ids)) > 0);
    }
}

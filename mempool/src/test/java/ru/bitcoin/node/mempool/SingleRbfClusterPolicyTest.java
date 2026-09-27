package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SingleRbfClusterPolicyTest {
    private static final MempoolLimits LIMITS = MempoolLimits.DEFAULT;

    @Test
    void replacementMayAddAnUnconfirmedInputWhenDiagramAndFeesImprove() {
        Transaction original = tx(List.of(external(1)), 1);
        Transaction unrelatedParent = tx(List.of(external(2)), 2);
        var old = new LinkedHashMap<Hash256, MempoolEntry>();
        old.put(original.txId(), entry(original, 1_000));
        old.put(unrelatedParent.txId(), entry(unrelatedParent, 20_000));

        Transaction replacement = tx(List.of(external(1), point(unrelatedParent)), 3);
        MempoolEntry replacementEntry = entry(replacement, 25_000);

        assertDoesNotThrow(() -> MempoolGraphPolicy.replacement(
                old, Set.of(original.txId()), Set.of(original.txId()), replacementEntry, LIMITS));
    }

    @Test
    void replacementLimitCountsDistinctConflictingClustersNotEvictedTransactions() {
        Transaction root = tx(List.of(external(1)), 1);
        var old = new LinkedHashMap<Hash256, MempoolEntry>();
        old.put(root.txId(), entry(root, 100));
        Transaction previous = root;
        for (int i = 0; i < 120; i++) {
            Transaction child = tx(List.of(point(previous)), i + 10);
            old.put(child.txId(), entry(child, 100));
            previous = child;
        }
        Set<Hash256> evicted = MempoolGraphPolicy.descendants(old, Set.of(root.txId()));
        Transaction replacement = tx(List.of(external(1)), 500);
        long oldFee = evicted.stream().mapToLong(id -> old.get(id).fee()).sum();
        assertDoesNotThrow(() -> MempoolGraphPolicy.replacement(
                old, Set.of(root.txId()), evicted, entry(replacement, oldFee + 100_000), LIMITS));
    }

    @Test
    void moreThanOneHundredDirectConflictClustersAreRejected() {
        var old = new LinkedHashMap<Hash256, MempoolEntry>();
        var conflicts = new LinkedHashSet<Hash256>();
        for (int i = 0; i < 101; i++) {
            Transaction conflict = tx(List.of(external(i + 1)), i + 1);
            old.put(conflict.txId(), entry(conflict, 100));
            conflicts.add(conflict.txId());
        }
        Transaction replacement = tx(List.of(external(500)), 999);
        var ex = assertThrows(MempoolAdmissionException.class, () -> MempoolGraphPolicy.replacement(
                old, conflicts, conflicts, entry(replacement, 1_000_000), LIMITS));
        assertEquals("too-many-conflicting-clusters", ex.getMessage());
    }

    private static MempoolEntry entry(Transaction tx, long fee) {
        return new MempoolEntry(tx, fee, TransactionWeight.calculate(tx), 0, 0);
    }

    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag)), new UInt32(0));
    }

    private static OutPoint point(Transaction tx) {
        return new OutPoint(tx.txId(), new UInt32(0));
    }

    private static Transaction tx(List<OutPoint> inputs, int tag) {
        return new Transaction(2,
                inputs.stream().map(p -> new TxIn(p, new byte[0], TxIn.FINAL_SEQUENCE)).toList(),
                List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }
}

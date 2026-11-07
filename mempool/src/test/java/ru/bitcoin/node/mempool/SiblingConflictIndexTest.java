package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class SiblingConflictIndexTest {
    @Test void indexedCandidateMatchesDescendantReferenceOnReorgShapedDags() {
        var random = new Random(32705);
        for (int trial = 0; trial < 500; trial++) {
            var entries = new LinkedHashMap<Hash256, MempoolEntry>();
            var transactions = new ArrayList<Transaction>();
            var spent = new HashMap<OutPoint, Hash256>();
            for (int i = 0; i < 20; i++) {
                var inputs = new ArrayList<OutPoint>();
                for (var parent : transactions) if (random.nextInt(12) == 0) inputs.add(point(parent, i));
                if (inputs.isEmpty()) inputs.add(external(i));
                long[] outputs = new long[32]; Arrays.fill(outputs, 1000 + i);
                var tx = tx(inputs, random.nextBoolean() ? 3 : 2, outputs);
                transactions.add(tx); entries.put(tx.txId(), new MempoolEntry(tx, 100, 400, 0));
                for (var input : tx.inputs()) spent.put(input.previousOutput(), tx.txId());
            }
            var parent = transactions.get(random.nextInt(transactions.size()));
            var candidate = tx(List.of(point(parent, 31)), 3, 900);
            var expected = new HashSet<Hash256>();
            if (parent.version() == 3) {
                var family = MempoolGraphPolicy.descendants(entries, Set.of(parent.txId()));
                if (family.size() == 2) {
                    family.remove(parent.txId()); var sibling = family.iterator().next();
                    if (MempoolGraphPolicy.ancestors(entries, sibling).size() == 2) expected.add(sibling);
                }
            }
            var actual = new HashSet<Hash256>();
            MempoolGraphPolicy.addSiblingConflict(entries, spent, candidate, actual);
            assertEquals(expected, actual, "DAG " + trial);
        }
    }

    @Test void eligibleSiblingNeedsNoPoolIterationAmongTenThousandUnrelatedEntries() {
        var parent = tx(List.of(FUND), 3, 90000, 5000);
        var sibling = tx(List.of(point(parent, 0)), 3, 80000);
        var candidate = tx(List.of(point(parent, 1)), 3, 4000);
        var entries = new HashMap<Hash256, MempoolEntry>();
        entries.put(parent.txId(), new MempoolEntry(parent, 100, 400, 0));
        entries.put(sibling.txId(), new MempoolEntry(sibling, 1000, 400, 0));
        for (int i = 0; i < 10000; i++) {
            var tx = tx(List.of(external(i)), 2, 90000);
            entries.put(tx.txId(), new MempoolEntry(tx, 100, 400, 0));
        }
        Map<Hash256, MempoolEntry> guarded = new AbstractMap<>() {
            @Override public Set<Entry<Hash256, MempoolEntry>> entrySet() { throw new AssertionError("Whole-pool sibling scan"); }
            @Override public boolean containsKey(Object id) { return entries.containsKey(id); }
            @Override public MempoolEntry get(Object id) { return entries.get(id); }
        };
        var conflicts = new HashSet<Hash256>();
        MempoolGraphPolicy.addSiblingConflict(guarded, Map.of(point(parent, 0), sibling.txId()), candidate, conflicts);
        assertEquals(Set.of(sibling.txId()), conflicts);
    }
    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag + 1)), new UInt32(0));
    }
}

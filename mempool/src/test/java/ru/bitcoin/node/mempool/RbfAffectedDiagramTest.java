package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class RbfAffectedDiagramTest {
    @Test void affectedDecisionsMatchWholePoolAcrossRandomSplitsAndJoins() {
        var random = new Random(32703);
        for (int trial = 0; trial < 300; trial++) {
            var before = new LinkedHashMap<Hash256, MempoolEntry>();
            var ids = new ArrayList<Hash256>();
            for (int i = 0; i < 12; i++) {
                var inputs = new ArrayList<OutPoint>();
                for (var id : ids) if (random.nextInt(8) == 0) inputs.add(new OutPoint(id, new UInt32(0)));
                if (inputs.isEmpty()) inputs.add(external(i));
                var tx = tx(inputs, 2, 90000 + i);
                before.put(tx.txId(), new MempoolEntry(tx, 10000, 300 + random.nextInt(700), 0,
                        0, random.nextInt(14000) - 12000));
                ids.add(tx.txId());
            }
            var conflicts = Set.of(ids.get(random.nextInt(ids.size())));
            var removed = MempoolGraphPolicy.descendants(before, conflicts);
            var inputs = new ArrayList<OutPoint>(); inputs.add(external(100));
            for (var id : ids) if (!removed.contains(id) && random.nextInt(4) == 0) inputs.add(new OutPoint(id, new UInt32(0)));
            long oldFee = removed.stream().mapToLong(id -> before.get(id).modifiedFee()).sum();
            var replacement = new MempoolEntry(tx(inputs, 2, 50000), Math.max(100, oldFee + random.nextInt(10000)), 300 + random.nextInt(2000), 0);
            var after = new LinkedHashMap<>(before); removed.forEach(after::remove);
            after.put(replacement.transaction().txId(), replacement);
            assertEquals(ClusterLinearization.compareDiagrams(ClusterLinearization.mempoolDiagram(after),
                            ClusterLinearization.mempoolDiagram(before)),
                    MempoolGraphPolicy.compareReplacementDiagrams(before, after, conflicts, replacement), "DAG " + trial);
        }
    }

    @Test void unrelatedEntriesAreNotReadForDiagramOptimization() {
        var before = new LinkedHashMap<Hash256, MempoolEntry>();
        var original = new MempoolEntry(tx(List.of(FUND), 2, 90000), 1000, 400, 0);
        before.put(original.transaction().txId(), original);
        for (int i = 0; i < 10000; i++) {
            var tx = tx(List.of(external(i)), 2, 80000);
            before.put(tx.txId(), new MempoolEntry(tx, 100 + i, 400, 0));
        }
        var replacement = new MempoolEntry(tx(List.of(FUND), 2, 80000), 2000, 400, 0);
        var after = new LinkedHashMap<>(before); after.remove(original.transaction().txId());
        after.put(replacement.transaction().txId(), replacement);
        var oldRoot = original.transaction().txId(); var newRoot = replacement.transaction().txId();
        assertTrue(MempoolGraphPolicy.compareReplacementDiagrams(guard(before, oldRoot), guard(after, newRoot),
                Set.of(oldRoot), replacement) > 0);
    }

    private static Map<Hash256, MempoolEntry> guard(Map<Hash256, MempoolEntry> entries, Hash256 allowed) {
        return new AbstractMap<>() {
            @Override public Set<Entry<Hash256, MempoolEntry>> entrySet() { return entries.entrySet(); }
            @Override public Set<Hash256> keySet() { return entries.keySet(); }
            @Override public MempoolEntry get(Object id) {
                if (!allowed.equals(id)) throw new AssertionError("Unrelated entry optimized: " + id);
                return entries.get(id);
            }
        };
    }
    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag + 1)), new UInt32(0));
    }
}

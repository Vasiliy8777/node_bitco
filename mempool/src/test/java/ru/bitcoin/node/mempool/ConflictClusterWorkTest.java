package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class ConflictClusterWorkTest {
    @Test void boundedCountsMatchIndependentUnionFindAcrossRandomDags() {
        var random = new Random(32612);
        for (int trial = 0; trial < 200; trial++) {
            var entries = new LinkedHashMap<Hash256, MempoolEntry>();
            var ids = new ArrayList<Hash256>();
            for (int i = 0, count = 1 + random.nextInt(40); i < count; i++) {
                var inputs = new ArrayList<OutPoint>();
                for (var parent : ids) if (random.nextInt(10) == 0) inputs.add(new OutPoint(parent, new UInt32(0)));
                if (inputs.isEmpty()) inputs.add(external(100000 + i));
                var transaction = tx(inputs, 2, 90000 + i);
                entries.put(transaction.txId(), new MempoolEntry(transaction, 100, 400, 0));
                ids.add(transaction.txId());
            }
            var roots = new LinkedHashSet<Hash256>();
            for (var id : ids) if (random.nextBoolean()) roots.add(id);
            int expected = reference(entries, roots);
            for (int maximum : new int[]{0, 1, 3, 100})
                assertEquals(Math.min(expected, maximum + 1),
                        ClusterLinearization.countConflictingClusters(entries, roots, maximum), "DAG " + trial);
        }
    }

    @Test void oneGraphBuildSufficesForManyConflictsInLargeUnrelatedPool() {
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        var roots = new LinkedHashSet<Hash256>();
        for (int i = 0; i < 10000; i++) {
            var transaction = tx(List.of(external(i)), 2, 90000);
            entries.put(transaction.txId(), new MempoolEntry(transaction, 100, 400, 0));
            if (i < 101) roots.add(transaction.txId());
        }
        var counted = new CountingMap(entries);
        var error = assertThrows(MempoolAdmissionException.class,
                () -> MempoolGraphPolicy.checkConflictClusterLimit(counted, roots, "over-bound"));
        assertEquals("over-bound", error.getMessage());
        assertTrue(counted.reads <= 2 * entries.size(), "Full-pool entry reads: " + counted.reads);
    }

    @Test void emptyConflictsDoNotBuildConnectivityGraph() {
        var transaction = tx(List.of(external(1)), 2, 90000);
        var counted = new CountingMap(Map.of(transaction.txId(), new MempoolEntry(transaction, 100, 400, 0)));
        assertEquals(0, ClusterLinearization.countConflictingClusters(counted, Set.of(), 100));
        assertEquals(0, counted.reads);
    }

    private static int reference(Map<Hash256, MempoolEntry> entries, Set<Hash256> roots) {
        var positions = new HashMap<Hash256, Integer>();
        int[] parent = new int[entries.size()];
        for (var id : entries.keySet()) { int i = positions.size(); positions.put(id, i); parent[i] = i; }
        for (var entry : entries.entrySet()) for (var input : entry.getValue().transaction().inputs()) {
            Integer p = positions.get(input.previousOutput().transactionId());
            if (p != null) parent[find(parent, positions.get(entry.getKey()))] = find(parent, p);
        }
        var components = new HashSet<Integer>();
        for (var root : roots) components.add(find(parent, positions.get(root)));
        return components.size();
    }
    private static int find(int[] parents, int x) {
        while (parents[x] != x) { parents[x] = parents[parents[x]]; x = parents[x]; }
        return x;
    }
    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag + 1)), new UInt32(0));
    }
    private static final class CountingMap extends AbstractMap<Hash256, MempoolEntry> {
        private final Map<Hash256, MempoolEntry> entries;
        int reads;
        CountingMap(Map<Hash256, MempoolEntry> entries) { this.entries = entries; }
        @Override public Set<Hash256> keySet() { return entries.keySet(); }
        @Override public int size() { return entries.size(); }
        @Override public Set<Entry<Hash256, MempoolEntry>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return entries.size(); }
                @Override public Iterator<Entry<Hash256, MempoolEntry>> iterator() {
                    var iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<Hash256, MempoolEntry> next() {
                            if (++reads > 2 * entries.size()) throw new AssertionError("Repeated connectivity rebuild");
                            return iterator.next();
                        }
                    };
                }
            };
        }
    }
}

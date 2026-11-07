package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class DescendantTraversalWorkTest {
    @Test void shuffledDagsMatchFixedPointReference() {
        var random = new Random(32613);
        for (int trial = 0; trial < 300; trial++) {
            var transactions = new ArrayList<Transaction>();
            for (int i = 0; i < 40; i++) {
                var inputs = new ArrayList<OutPoint>();
                for (var parent : transactions) if (random.nextInt(12) == 0) inputs.add(point(parent, 0));
                if (inputs.isEmpty()) inputs.add(external(i));
                transactions.add(tx(inputs, 2, 90000 + i));
            }
            Collections.shuffle(transactions, random);
            var entries = new LinkedHashMap<Hash256, MempoolEntry>();
            var roots = new HashSet<Hash256>();
            for (var transaction : transactions) {
                entries.put(transaction.txId(), new MempoolEntry(transaction, 100, 400, 0));
                if (random.nextInt(8) == 0) roots.add(transaction.txId());
            }
            if (trial % 2 == 0) roots.add(external(random.nextInt(40)).transactionId());
            assertEquals(reference(entries, roots), MempoolGraphPolicy.descendants(entries, roots), "DAG " + trial);
        }
    }

    @Test void reverseOrderedTenThousandEntryChainNeedsOnePoolPass() {
        var ordered = new ArrayList<Transaction>();
        var previous = external(0);
        for (int i = 0; i < 10000; i++) {
            var transaction = tx(List.of(previous), 2, 90000);
            ordered.add(transaction);
            previous = point(transaction, 0);
        }
        Collections.reverse(ordered);
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var transaction : ordered) entries.put(transaction.txId(), new MempoolEntry(transaction, 100, 400, 0));
        var counted = new CountingMap(entries);
        assertEquals(entries.keySet(), MempoolGraphPolicy.descendants(counted, Set.of(ordered.getLast().txId())));
        assertEquals(entries.size(), counted.reads);
    }

    @Test void absentRootStillFindsChildrenAndRetainsRoot() {
        var child = tx(List.of(external(5), external(5)), 2, 90000);
        var entries = Map.of(child.txId(), new MempoolEntry(child, 100, 400, 0));
        assertEquals(Set.of(external(5).transactionId(), child.txId()),
                MempoolGraphPolicy.descendants(entries, Set.of(external(5).transactionId())));
    }

    @Test void emptyRootsNeverReadPool() {
        var child = tx(List.of(external(0)), 2, 90000);
        var counted = new CountingMap(Map.of(child.txId(), new MempoolEntry(child, 100, 400, 0)));
        assertEquals(Set.of(), MempoolGraphPolicy.descendants(counted, Set.of()));
        assertEquals(0, counted.reads);
    }

    private static Set<Hash256> reference(Map<Hash256, MempoolEntry> entries, Set<Hash256> roots) {
        var result = new HashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var entry : entries.entrySet())
                for (var input : entry.getValue().transaction().inputs())
                    if (result.contains(input.previousOutput().transactionId())) changed |= result.add(entry.getKey());
        } while (changed);
        return result;
    }

    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag + 1)), new UInt32(0));
    }

    private static final class CountingMap extends AbstractMap<Hash256, MempoolEntry> {
        private final Map<Hash256, MempoolEntry> entries;
        int reads;
        CountingMap(Map<Hash256, MempoolEntry> entries) { this.entries = entries; }
        @Override public Set<Entry<Hash256, MempoolEntry>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return entries.size(); }
                @Override public Iterator<Entry<Hash256, MempoolEntry>> iterator() {
                    var iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<Hash256, MempoolEntry> next() {
                            if (++reads > entries.size()) throw new AssertionError("Repeated whole-pool traversal");
                            return iterator.next();
                        }
                    };
                }
            };
        }
    }
}

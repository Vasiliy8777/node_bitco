package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class EvictionTailTraversalTest {
    @Test void finalChunksAlreadyContainEveryDescendantAcrossRandomDags() {
        var random = new Random(32704);
        for (int trial = 0; trial < 300; trial++) {
            var entries = new LinkedHashMap<Hash256, MempoolEntry>();
            var ids = new ArrayList<Hash256>();
            for (int i = 0; i < 16; i++) {
                var inputs = new ArrayList<OutPoint>();
                for (var id : ids) if (random.nextInt(9) == 0) inputs.add(new OutPoint(id, new UInt32(0)));
                if (inputs.isEmpty()) inputs.add(external(i));
                var tx = tx(inputs, 2, 90000 + i);
                entries.put(tx.txId(), new MempoolEntry(tx, 10000, 300 + random.nextInt(700), 0,
                        random.nextInt(15), random.nextInt(14000) - 12000));
                ids.add(tx.txId());
            }
            for (var cluster : ClusterLinearization.clusters(entries)) {
                var tail = ClusterLinearization.chunks(entries, cluster).getLast();
                var descendants = new HashSet<>(tail.transactions());
                boolean changed;
                do {
                    changed = false;
                    for (var entry : entries.entrySet())
                        for (var input : entry.getValue().transaction().inputs())
                            if (descendants.contains(input.previousOutput().transactionId())) changed |= descendants.add(entry.getKey());
                } while (changed);
                assertEquals(new HashSet<>(tail.transactions()), descendants, "DAG " + trial);
                assertEquals(tail.fee(), descendants.stream().mapToLong(id -> entries.get(id).modifiedFee()).sum());
                assertEquals(tail.adjustedWeight(), descendants.stream().mapToLong(id -> entries.get(id).adjustedWeight()).sum());
            }
        }
    }

    @Test void selectingOneEvictionBuildsWholePoolEdgesOnlyOnce() {
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        Hash256 cheapest = null;
        for (int i = 0; i < 3000; i++) {
            var tx = tx(List.of(external(i)), 2, 90000);
            entries.put(tx.txId(), new MempoolEntry(tx, 100 + i, 400, 0));
            if (i == 0) cheapest = tx.txId();
        }
        var counted = new CountingMap(entries);
        assertEquals(1000, MempoolGraphPolicy.trim(counted, 299900, null));
        assertEquals(2999, entries.size()); assertFalse(entries.containsKey(cheapest));
        assertEquals(3000, counted.reads);
    }

    private static OutPoint external(int tag) {
        return new OutPoint(Hash256.fromDisplayHex(String.format("%064x", tag + 1)), new UInt32(0));
    }
    private static final class CountingMap extends AbstractMap<Hash256, MempoolEntry> {
        private final Map<Hash256, MempoolEntry> entries;
        int reads;
        CountingMap(Map<Hash256, MempoolEntry> entries) { this.entries = entries; }
        @Override public Set<Hash256> keySet() { return entries.keySet(); }
        @Override public MempoolEntry get(Object id) { return entries.get(id); }
        @Override public MempoolEntry remove(Object id) { return entries.remove(id); }
        @Override public Set<Entry<Hash256, MempoolEntry>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return entries.size(); }
                @Override public Iterator<Entry<Hash256, MempoolEntry>> iterator() {
                    var iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<Hash256, MempoolEntry> next() {
                            if (++reads > 3000) throw new AssertionError("Repeated whole-pool child-edge build");
                            return iterator.next();
                        }
                    };
                }
            };
        }
    }
}

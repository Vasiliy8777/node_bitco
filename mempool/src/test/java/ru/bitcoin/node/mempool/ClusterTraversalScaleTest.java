package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterTraversalScaleTest {
    private static Transaction tx(List<Hash256> parents, int tag) {
        return new Transaction(2, parents.stream().map(p -> new TxIn(new OutPoint(p, new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE)).toList(), List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }

    @Test
    void independentTransactionsDoNotCauseQuadraticPoolScans() {
        var entries = new CountingMap();
        var confirmed = Hash256.fromDisplayHex("11".repeat(32));
        for (int i = 0; i < 5000; i++) {
            var tx = tx(List.of(confirmed), i);
            entries.put(tx.txId(), new MempoolEntry(tx, 1000, 400, 0, 0));
        }
        var clusters = ClusterLinearization.clusters(entries);
        assertEquals(entries.size(), clusters.size());
        assertTrue(clusters.stream().allMatch(c -> c.size() == 1));
        assertTrue(entries.scanned <= entries.size() * 4L,
                "Cluster discovery must not scan the whole pool for every transaction: " + entries.scanned);
    }

    @Test
    void connectivityIncludesBothDirectionsAndMultipleParentsButNotConfirmedParents() {
        var confirmed = Hash256.fromDisplayHex("22".repeat(32));
        var a = tx(List.of(confirmed), 1);
        var b = tx(List.of(confirmed), 2);
        var child = tx(List.of(a.txId(), b.txId(), a.txId()), 3);
        var unrelated = tx(List.of(confirmed), 4);
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var t : List.of(child, unrelated, b, a)) entries.put(t.txId(), new MempoolEntry(t, 1000, 400, 0, 0));
        var component = Set.of(a.txId(), b.txId(), child.txId());
        assertEquals(List.of(component, Set.of(unrelated.txId())), ClusterLinearization.clusters(entries));
        assertEquals(component, ClusterLinearization.connected(entries, List.of(b.txId())));
        assertEquals(entries.keySet(), ClusterLinearization.connected(entries, List.of(child.txId(), unrelated.txId())));
        assertTrue(ClusterLinearization.connected(entries, List.of(confirmed)).isEmpty());
    }

    private static final class CountingMap extends LinkedHashMap<Hash256, MempoolEntry> {
        private long scanned;
        @Override public Set<Map.Entry<Hash256, MempoolEntry>> entrySet() {
            var delegate = super.entrySet();
            return new AbstractSet<>() {
                @Override public int size() { return delegate.size(); }
                @Override public Iterator<Map.Entry<Hash256, MempoolEntry>> iterator() {
                    var iterator = delegate.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Map.Entry<Hash256, MempoolEntry> next() { scanned++; return iterator.next(); }
                    };
                }
            };
        }
    }
}

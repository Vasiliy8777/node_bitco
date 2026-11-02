package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClusterDiagramCacheTest {
    @Test void onlyChangedClustersAreRelinearizedAndRemovedGraphsAreForgotten() {
        var calls = new AtomicInteger();
        var cache = countingCache(100, calls);
        var parent = entry(100, 1, external(1));
        var child = entry(10000, 2, parent.transaction().txId());
        var unrelated = entry(900, 3, external(2));
        var entries = map(parent, child, unrelated);
        assertDiagram(cache, entries); assertEquals(2, calls.get());
        assertDiagram(cache, new LinkedHashMap<>(entries)); assertEquals(2, calls.get(), "Unchanged entries must not run the solver");
        entries.put(parent.transaction().txId(), parent.withFeeDelta(20000));
        assertDiagram(cache, entries); assertEquals(3, calls.get(), "Fee delta changes only its cluster");
        entries.remove(parent.transaction().txId()); // Connected parent: child becomes independent.
        assertDiagram(cache, entries); assertEquals(4, calls.get());
        entries.put(parent.transaction().txId(), parent); // Reorg resurrects the dependency.
        assertDiagram(cache, entries); assertEquals(5, calls.get());
        entries.clear(); assertDiagram(cache, entries);
        entries.putAll(map(parent, child, unrelated));
        assertDiagram(cache, entries); assertEquals(7, calls.get(), "An empty pool must release all cached graphs");
    }

    @Test void mergeReplacementSigopsAndSplitsMatchUncachedSolver() {
        var cache = new ClusterDiagramCache();
        var a = entry(10, 1, external(1)); var b = entry(20, 2, external(2));
        var entries = map(a, b); assertDiagram(cache, entries);
        var join = entry(5000, 3, a.transaction().txId(), b.transaction().txId());
        entries.put(join.transaction().txId(), join); assertDiagram(cache, entries);
        entries.put(join.transaction().txId(), new MempoolEntry(join.transaction(), join.fee(), join.weight(), 0, 100));
        assertDiagram(cache, entries);
        entries.remove(join.transaction().txId()); assertDiagram(cache, entries);
        var replacement = entry(8000, 4, a.transaction().txId());
        entries.put(replacement.transaction().txId(), replacement); assertDiagram(cache, entries);
        entries.remove(b.transaction().txId()); assertDiagram(cache, entries);
    }

    @Test void cacheCapacityIsBoundedByTransactionsAndNeverAffectsResults() {
        var calls = new AtomicInteger(); var cache = countingCache(2, calls);
        var a = entry(10, 1, external(1)); var b = entry(20, 2, a.transaction().txId());
        var c = entry(30, 3, b.transaction().txId());
        var entries = map(a, b, c);
        assertDiagram(cache, entries); assertDiagram(cache, entries);
        assertEquals(2, calls.get(), "A cluster larger than capacity must not be retained");
        entries.remove(c.transaction().txId());
        assertDiagram(cache, entries); assertDiagram(cache, entries);
        assertEquals(3, calls.get());
    }

    @Test void randomizedAddRemoveAndFeeChangesPreserveTheExactDiagram() {
        var random = new Random(320102); var cache = new ClusterDiagramCache();
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        for (int step = 0; step < 240; step++) {
            if (entries.isEmpty() || (entries.size() < 32 && random.nextBoolean())) {
                var parents = new ArrayList<Hash256>();
                for (var id : entries.keySet()) if (random.nextInt(8) == 0) parents.add(id);
                if (parents.isEmpty()) parents.add(external(step + 1));
                var entry = entry(random.nextInt(10000), step + 1, parents.toArray(Hash256[]::new));
                entries.put(entry.transaction().txId(), entry);
            } else {
                var id = new ArrayList<>(entries.keySet()).get(random.nextInt(entries.size()));
                if (random.nextBoolean()) entries.remove(id);
                else entries.put(id, entries.get(id).withFeeDelta(random.nextInt(20000) - 10000));
            }
            assertDiagram(cache, entries);
            assertDiagram(cache, entries);
        }
    }

    @Test void repeatedLargeSnapshotsAvoidAllUnchangedClusterSolves() {
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        for (int cluster = 0; cluster < 128; cluster++) {
            var parent = entry(0, cluster * 100 + 1, external(cluster + 10000));
            entries.put(parent.transaction().txId(), parent);
            for (int child = 1; child <= 15; child++) {
                var tx = entry(1000 + child, cluster * 100 + child + 1, parent.transaction().txId());
                entries.put(tx.transaction().txId(), tx);
            }
        }
        var calls = new AtomicInteger(); var cache = countingCache(65536, calls);
        var expected = ClusterLinearization.mempoolDiagram(entries);
        assertEquals(expected, ClusterLinearization.mergeChunks(cache.clusters(entries)));
        long cold = System.nanoTime();
        for (int i = 0; i < 8; i++) assertEquals(expected, ClusterLinearization.mempoolDiagram(entries));
        long coldNanos = System.nanoTime() - cold;
        long warm = System.nanoTime();
        for (int i = 0; i < 8; i++) assertEquals(expected, ClusterLinearization.mergeChunks(cache.clusters(entries)));
        long warmNanos = System.nanoTime() - warm;
        assertEquals(128, calls.get(), "Repeated reads must never rerun an unchanged cluster solver");
        System.out.println("CORE32_CLUSTER_CACHE entries=" + entries.size() + " repeats=8 uncached_ms="
                + coldNanos / 1_000_000.0 + " cached_ms=" + warmNanos / 1_000_000.0 + " solver_calls=" + calls.get());
    }

    private static ClusterDiagramCache countingCache(int capacity, AtomicInteger calls) {
        return new ClusterDiagramCache(capacity, (entries, ids) -> { calls.incrementAndGet(); return ClusterLinearization.chunks(entries, ids); });
    }
    private static void assertDiagram(ClusterDiagramCache cache, Map<Hash256, MempoolEntry> entries) {
        assertEquals(ClusterLinearization.mempoolDiagram(entries), ClusterLinearization.mergeChunks(cache.clusters(entries)));
    }
    private static MempoolEntry entry(long fee, int tag, Hash256... parents) {
        var inputs = Arrays.stream(parents).map(id -> new TxIn(new OutPoint(id, new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)).toList();
        return new MempoolEntry(new Transaction(2, inputs, List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0)), fee, 400, 0);
    }
    private static Hash256 external(int tag) { return Hash256.fromDisplayHex(String.format("%064x", tag)); }
    private static LinkedHashMap<Hash256, MempoolEntry> map(MempoolEntry... entries) {
        var result = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var entry : entries) result.put(entry.transaction().txId(), entry);
        return result;
    }
}

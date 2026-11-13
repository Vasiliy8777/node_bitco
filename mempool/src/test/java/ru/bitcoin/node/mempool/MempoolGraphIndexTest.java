package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MempoolGraphIndexTest {
    @Test void multiRootClosureDeduplicatesSharedChildrenAndIgnoresMissingVertices() {
        var index = new MempoolGraphIndex();
        index.add(id(1), Set.of(), Set.of()); index.add(id(2), Set.of(), Set.of());
        index.add(id(3), Set.of(id(1), id(2)), Set.of());
        index.add(id(4), Set.of(id(3)), Set.of());
        assertEquals(Set.of(id(1), id(2), id(3), id(4)), index.descendants(List.of(id(1), id(2), id(1), id(99))));
        assertEquals(Set.of(id(1), id(2), id(3), id(4)), index.ancestors(id(4)));
        assertEquals(Set.of(id(1), id(2)), index.parents(id(3)));
        assertEquals(Set.of(id(3)), index.children(id(2)));
        var published = index.component(id(1));
        assertThrows(UnsupportedOperationException.class, () -> published.add(id(9)));
        index.removeAll(Set.of(id(3)));
        assertEquals(Set.of(id(1), id(2), id(3), id(4)), published);
        assertTrue(index.ancestors(id(99)).isEmpty()); assertTrue(index.descendants(List.of()).isEmpty());
        assertTrue(index.parents(id(99)).isEmpty()); assertTrue(index.children(id(99)).isEmpty());
        assertTrue(index.component(id(99)).isEmpty());
    }
    @Test void copiedGraphsHaveIndependentAdjacencyAndComponentMembership() {
        var source = new MempoolGraphIndex();
        source.add(id(1), Set.of(), Set.of());
        source.add(id(2), Set.of(id(1)), Set.of());
        source.add(id(3), Set.of(), Set.of());
        var before = source.components();
        var copy = new MempoolGraphIndex(); copy.copyFrom(source);
        assertSame(before, copy.components(), "Only immutable snapshots may be shared");
        source.removeAll(Set.of(id(2)));
        assertEquals(Set.of(id(1), id(2)), copy.descendants(id(1)));
        copy.add(id(4), Set.of(id(2), id(3)), Set.of());
        assertEquals(Set.of(Set.of(id(1)), Set.of(id(3))), new HashSet<>(source.components()));
        assertEquals(Set.of(Set.of(id(1), id(2)), Set.of(id(3))), new HashSet<>(before));
        assertEquals(Set.of(id(1), id(2), id(4)), copy.descendants(id(1)));
        copy.removeAll(Set.of(id(1))); // Confirmed parent, children survive.
        assertEquals(Set.of(Set.of(id(2), id(3), id(4))), new HashSet<>(copy.components()));
        assertEquals(Set.of(id(1)), source.descendants(id(1)));
    }

    @Test void repeatedCopyAdvancesDestinationVersionAndDiscardsOldVertices() {
        var source = new MempoolGraphIndex(); source.add(id(1), Set.of(), Set.of());
        var destination = new MempoolGraphIndex();
        destination.add(id(9), Set.of(), Set.of());
        for (int i = 0; i < 10; i++) {
            long revision = destination.revision();
            destination.copyFrom(source);
            assertTrue(destination.revision() > revision);
            assertEquals(Set.of(Set.of(id(1))), new HashSet<>(destination.components()));
            destination.add(id(10 + i), Set.of(id(1)), Set.of());
            assertEquals(Set.of(id(1)), source.descendants(id(1)));
        }
        destination.copyFrom(new MempoolGraphIndex());
        assertTrue(destination.components().isEmpty());
        assertTrue(destination.descendants(id(1)).isEmpty());
    }

    @Test void unpublishedComponentSnapshotsAreNotSharedAsMutableState() {
        var source = new MempoolGraphIndex();
        source.add(id(1), Set.of(), Set.of()); source.add(id(2), Set.of(id(1)), Set.of());
        var copy = new MempoolGraphIndex(); copy.copyFrom(source); // No snapshot published yet.
        source.add(id(3), Set.of(id(2)), Set.of());
        copy.removeAll(Set.of(id(2)));
        assertEquals(Set.of(Set.of(id(1), id(2), id(3))), new HashSet<>(source.components()));
        assertEquals(Set.of(Set.of(id(1))), new HashSet<>(copy.components()));
    }
    @Test void committedDeltaPreservesUnchangedComponentsWithoutReadingTheirEntries() {
        var first = entry(1, id(900)); var independent = entry(2, id(901));
        var entries = entries(first, independent);
        var index = new MempoolGraphIndex(); index.rebuild(entries);
        var independentComponent = index.components().stream()
                .filter(component -> component.contains(independent.transaction().txId())).findFirst().orElseThrow();
        var child = entry(3, first.transaction().txId());
        entries.put(child.transaction().txId(), child);
        var spent = spent(entries);
        var guarded = new AbstractMap<Hash256, MempoolEntry>() {
            @Override public Set<Map.Entry<Hash256, MempoolEntry>> entrySet() {
                var result = new LinkedHashSet<Map.Entry<Hash256, MempoolEntry>>();
                for (var item : entries.entrySet()) result.add(new SimpleImmutableEntry<>(item) {
                    @Override public MempoolEntry getValue() {
                        if (!getKey().equals(child.transaction().txId()))
                            throw new AssertionError("Unchanged entry must not be read by graph reconciliation");
                        return super.getValue();
                    }
                });
                return result;
            }
        };
        index.reconcile(guarded, spent);
        assertEquals(Set.of(first.transaction().txId(), child.transaction().txId()), index.descendants(first.transaction().txId()));
        assertSame(independentComponent, index.components().stream()
                .filter(component -> component.contains(independent.transaction().txId())).findFirst().orElseThrow());
        var components = index.components(); long revision = index.revision();
        entries.put(first.transaction().txId(), first.withFeeDelta(100));
        index.reconcile(entries, spent);
        assertSame(components, index.components());
        assertTrue(index.revision() > revision, "Metadata-only commit must invalidate chunk scores");
    }

    @Test void reverseOrderLateParentsAndRemovalMatchFullRebuild() {
        var parent = entry(10, id(900));
        var child = entry(11, parent.transaction().txId());
        var grandchild = entry(12, child.transaction().txId());
        var index = new MempoolGraphIndex();
        var entries = entries(grandchild, child); // Parent confirmed, children already in the pool.
        index.reconcile(entries, spent(entries));
        entries.put(parent.transaction().txId(), parent); // Disconnected parent arrives last.
        index.reconcile(entries, spent(entries));
        assertEquals(entries.keySet(), index.descendants(parent.transaction().txId()));
        entries.remove(child.transaction().txId());
        index.reconcile(entries, spent(entries));
        assertEquals(Set.of(Set.of(parent.transaction().txId()), Set.of(grandchild.transaction().txId())), new HashSet<>(index.components()));
    }

    @Test void randomizedBulkCommitsMatchFullGraphAndUncachedDiagrams() {
        var random = new Random(320112);
        var available = new ArrayList<MempoolEntry>();
        for (int tag = 1; tag <= 45; tag++) {
            var parent = tag > 1 && random.nextBoolean()
                    ? available.get(random.nextInt(available.size())).transaction().txId() : id(1000 + tag);
            available.add(entry(tag, parent));
        }
        var index = new MempoolGraphIndex(); var cache = new ClusterDiagramCache();
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        for (int step = 0; step < 200; step++) {
            // Select subsets and arbitrary order, including parents restored after children.
            Collections.shuffle(available, random); entries.clear();
            for (var entry : available) if (random.nextBoolean())
                entries.put(entry.transaction().txId(), entry.withFeeDelta(random.nextInt(1000) - 500));
            index.reconcile(entries, spent(entries));
            var rebuilt = new MempoolGraphIndex(); rebuilt.rebuild(entries);
            assertEquals(new HashSet<>(rebuilt.components()), new HashSet<>(index.components()), "step " + step);
            for (var id : entries.keySet()) assertEquals(rebuilt.descendants(id), index.descendants(id));
            assertEquals(ClusterLinearization.mempoolDiagram(entries), ClusterLinearization.mergeChunks(
                    cache.clusters(entries, index.components(), index.revision())));
        }
    }

    private static MempoolEntry entry(int tag, Hash256 parent) {
        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(parent, new UInt32(tag)), new byte[0], TxIn.FINAL_SEQUENCE)),
                java.util.stream.IntStream.range(0, 46).mapToObj(i -> new TxOut(tag, new byte[]{0x51})).toList(), new UInt32(0));
        return new MempoolEntry(tx, tag * 100L, 2000, 0);
    }
    private static LinkedHashMap<Hash256, MempoolEntry> entries(MempoolEntry... values) {
        var result = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var value : values) result.put(value.transaction().txId(), value);
        return result;
    }
    private static Map<OutPoint, Hash256> spent(Map<Hash256, MempoolEntry> entries) {
        var result = new HashMap<OutPoint, Hash256>();
        entries.forEach((id, entry) -> entry.transaction().inputs().forEach(input -> result.put(input.previousOutput(), id)));
        return result;
    }
    @Test void randomizedChangesMatchIndependentReachability() {
        var index = new MempoolGraphIndex();
        var parents = new LinkedHashMap<Hash256, Set<Hash256>>();
        var random = new Random(320111);
        for (int step = 1; step <= 600; step++) {
            if (parents.isEmpty() || (parents.size() < 40 && random.nextBoolean())) {
                var id = id(step);
                var upstream = new HashSet<Hash256>();
                var downstream = new HashSet<Hash256>();
                for (var known : parents.keySet()) {
                    if (random.nextInt(12) == 0) upstream.add(known);
                    else if (random.nextInt(12) == 0) downstream.add(known);
                }
                index.add(id, upstream, downstream);
                parents.put(id, upstream);
                for (var child : downstream) parents.get(child).add(id);
            } else {
                var removed = new HashSet<Hash256>();
                for (var known : parents.keySet()) if (random.nextInt(6) == 0) removed.add(known);
                index.removeAll(removed);
                removed.forEach(parents::remove);
                parents.values().forEach(values -> values.removeAll(removed));
            }
            var expectedComponents = new HashSet<Set<Hash256>>();
            for (var root : parents.keySet()) {
                assertEquals(reachable(parents, root, false), index.descendants(root));
                expectedComponents.add(reachable(parents, root, true));
            }
            assertEquals(expectedComponents, new HashSet<>(index.components()), "step " + step);
            var view = index.components();
            long revision = index.revision();
            index.scoresChanged();
            assertEquals(revision + 1, index.revision());
            assertSame(view, index.components());
        }
    }

    @Test void lateParentSplitsAndPublishedSnapshotsRemainStable() {
        var index = new MempoolGraphIndex();
        index.add(id(1), Set.of(), Set.of());
        index.add(id(2), Set.of(), Set.of());
        var before = index.components();
        index.add(id(3), Set.of(), Set.of(id(1), id(2)));
        assertEquals(Set.of(id(1), id(2), id(3)), index.descendants(id(3)));
        assertEquals(Set.of(Set.of(id(1)), Set.of(id(2))), new HashSet<>(before));
        var joined = index.components();
        index.removeAll(Set.of(id(3)));
        assertEquals(new HashSet<>(before), new HashSet<>(index.components()));
        assertEquals(Set.of(Set.of(id(1), id(2), id(3))), new HashSet<>(joined));
        var unchanged = index.components();
        assertThrows(IllegalArgumentException.class, () -> index.add(id(4), Set.of(id(99)), Set.of()));
        assertSame(unchanged, index.components());
    }

    private static Set<Hash256> reachable(Map<Hash256, Set<Hash256>> parents, Hash256 root, boolean undirected) {
        var result = new HashSet<Hash256>(); result.add(root);
        boolean changed;
        do {
            changed = false;
            for (var entry : parents.entrySet()) {
                if (!Collections.disjoint(entry.getValue(), result)) changed |= result.add(entry.getKey());
                if (undirected && result.contains(entry.getKey())) changed |= result.addAll(entry.getValue());
            }
        } while (changed);
        return result;
    }
    private static Hash256 id(int value) { return Hash256.fromDisplayHex(String.format("%064x", value)); }
}

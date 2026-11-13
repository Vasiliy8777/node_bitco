package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the publication boundary independently of policy/script validation. */
class MempoolCommitIndexTest {
    @Test void randomizedCommitsMatchFreshSpentBytesAndDiagram() throws Exception {
        var random = new Random(320114); var pool = new Mempool();
        var available = new ArrayList<MempoolEntry>();
        for (int tag = 1; tag <= 35; tag++) {
            var parent = tag > 1 && random.nextBoolean()
                    ? available.get(random.nextInt(available.size())).transaction().txId() : id(1000 + tag);
            available.add(entry(tag, new OutPoint(parent, new UInt32(tag))));
        }
        var allInputs = new HashSet<OutPoint>();
        available.forEach(entry -> entry.transaction().inputs().forEach(input -> allInputs.add(input.previousOutput())));
        for (int step = 0; step < 160; step++) {
            Collections.shuffle(available, random);
            var candidate = new LinkedHashMap<Hash256, MempoolEntry>();
            for (var entry : available) if (random.nextBoolean())
                candidate.put(entry.transaction().txId(), entry.withFeeDelta(random.nextInt(1000) - 500));
            commit(pool, candidate);
            assertEquals(List.copyOf(candidate.values()), pool.entries());
            assertEquals(candidate.values().stream().mapToLong(MempoolEntry::virtualSize).sum(), bytes(pool));
            var expected = new HashMap<OutPoint, Transaction>();
            candidate.values().forEach(entry -> entry.transaction().inputs().forEach(input -> expected.put(input.previousOutput(), entry.transaction())));
            for (var point : allInputs) assertEquals(Optional.ofNullable(expected.get(point)), pool.spendingTransaction(point), "step " + step);
            assertEquals(ClusterLinearization.mempoolDiagram(candidate), pool.feeRateDiagram());
            assertGraphViews(pool, candidate);
        }
    }

    @Test void replacementReassignsSharedInputAndRemovesChildSpendsAndUnbroadcast() throws Exception {
        var pool = new Mempool(); var funding = new OutPoint(id(1000), new UInt32(0));
        var old = entry(1, funding); var replacement = entry(2, funding);
        var child = entry(3, new OutPoint(old.transaction().txId(), new UInt32(0)));
        var unrelated = entry(4, new OutPoint(id(1001), new UInt32(0)));
        commit(pool, map(old, child, unrelated));
        pool.markUnbroadcast(old.transaction().txId()); pool.markUnbroadcast(unrelated.transaction().txId());
        var candidate = map(replacement, unrelated); commit(pool, candidate);
        assertEquals(Optional.of(replacement.transaction()), pool.spendingTransaction(funding));
        assertTrue(pool.spendingTransaction(child.transaction().inputs().getFirst().previousOutput()).isEmpty());
        assertEquals(Set.of(unrelated.transaction().txId()), new HashSet<>(pool.unbroadcastTransactions()));
        candidate.clear(); assertEquals(2, pool.size(), "Candidate map must not alias committed entries");
        commit(pool, Map.of());
        assertEquals(0, bytes(pool)); assertTrue(pool.feeRateDiagram().isEmpty());
        assertTrue(pool.spendingTransaction(funding).isEmpty()); assertTrue(pool.unbroadcastTransactions().isEmpty());
    }

    @Test void sameTxidMetadataChangesUpdateByteAccountingAndChunks() throws Exception {
        var pool = new Mempool(); var original = entry(1, new OutPoint(id(1000), new UInt32(0)));
        commit(pool, map(original)); var before = pool.feeRateDiagram();
        var changed = new MempoolEntry(original.transaction(), original.fee(), original.weight(), 0, 100, 500);
        commit(pool, map(changed));
        assertEquals(changed.virtualSize(), bytes(pool));
        assertNotEquals(before, pool.feeRateDiagram());
        assertEquals(ClusterLinearization.mempoolDiagram(map(changed)), pool.feeRateDiagram());
        assertEquals(Optional.of(original.transaction()), pool.spendingTransaction(original.transaction().inputs().getFirst().previousOutput()));
    }

    @Test void accountingOverflowLeavesCommittedIndexesUntouched() throws Exception {
        var pool = new Mempool(); var original = entry(1, new OutPoint(id(1000), new UInt32(0)));
        commit(pool, map(original)); pool.markUnbroadcast(original.transaction().txId());
        var snapshot = pool.detailedSnapshot(); var diagram = pool.feeRateDiagram(); long bytes = bytes(pool);
        var invalid = new MempoolEntry(original.transaction(), original.fee(), original.weight(), 0, Long.MAX_VALUE);
        var error = assertThrows(InvocationTargetException.class, () -> commit(pool, map(invalid)));
        assertInstanceOf(ArithmeticException.class, error.getCause());
        assertEquals(snapshot, pool.detailedSnapshot()); assertEquals(diagram, pool.feeRateDiagram());
        assertEquals(bytes, bytes(pool)); assertEquals(List.of(original.transaction().txId()), List.copyOf(pool.unbroadcastTransactions()));
        assertEquals(Optional.of(original.transaction()), pool.spendingTransaction(original.transaction().inputs().getFirst().previousOutput()));
    }

    private static void commit(Mempool pool, Map<Hash256, MempoolEntry> candidate) throws Exception {
        var method = Mempool.class.getDeclaredMethod("commitEntries", Map.class); method.setAccessible(true);
        method.invoke(pool, candidate);
    }

    private static void assertGraphViews(Mempool pool, Map<Hash256, MempoolEntry> entries) {
        var expected = new HashMap<Hash256, Mempool.EntryGraphView>();
        var chunks = new HashMap<Hash256, ClusterLinearization.Chunk>();
        for (var cluster : ClusterLinearization.clusters(entries))
            for (var chunk : ClusterLinearization.chunks(entries, cluster))
                for (var member : chunk.transactions()) chunks.put(member, chunk);
        for (var item : entries.entrySet()) {
            var id = item.getKey(); var entry = item.getValue();
            var ancestors = MempoolGraphPolicy.ancestors(entries, id);
            var descendants = MempoolGraphPolicy.descendants(entries, Set.of(id));
            var parents = new HashSet<Hash256>(); var children = new HashSet<Hash256>();
            entry.transaction().inputs().forEach(input -> {
                if (entries.containsKey(input.previousOutput().transactionId())) parents.add(input.previousOutput().transactionId());
            });
            entries.forEach((child, value) -> {
                if (value.transaction().inputs().stream().anyMatch(input -> input.previousOutput().transactionId().equals(id))) children.add(child);
            });
            expected.put(id, new Mempool.EntryGraphView(entry, ancestors, descendants, parents, children, chunks.get(id),
                    ancestors.stream().mapToLong(value -> entries.get(value).virtualSize()).sum(),
                    descendants.stream().mapToLong(value -> entries.get(value).virtualSize()).sum(),
                    ancestors.stream().mapToLong(value -> entries.get(value).modifiedFee()).sum(),
                    descendants.stream().mapToLong(value -> entries.get(value).modifiedFee()).sum()));
        }
        assertEquals(expected, pool.graphViews(entries.keySet()));
        if (!entries.isEmpty()) {
            var root = entries.keySet().iterator().next(); var view = expected.get(root);
            var ancestors = new HashSet<>(view.ancestors()); ancestors.remove(root);
            var descendants = new HashSet<>(view.descendants()); descendants.remove(root);
            assertEquals(ancestors, new HashSet<>(pool.ancestorQuery(root).orElseThrow().txIds()));
            assertEquals(descendants, new HashSet<>(pool.descendantQuery(root).orElseThrow().txIds()));
        }
    }
    private static long bytes(Mempool pool) throws Exception {
        var field = Mempool.class.getDeclaredField("totalVirtualBytes"); field.setAccessible(true); return field.getLong(pool);
    }
    private static MempoolEntry entry(int tag, OutPoint funding) {
        var tx = new Transaction(2, List.of(new TxIn(funding, new byte[0], TxIn.FINAL_SEQUENCE)),
                java.util.stream.IntStream.range(0, 36).mapToObj(i -> new TxOut(tag, new byte[]{0x51})).toList(), new UInt32(0));
        return new MempoolEntry(tx, tag * 100L, 2000, 0);
    }
    private static LinkedHashMap<Hash256, MempoolEntry> map(MempoolEntry... values) {
        var result = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var value : values) result.put(value.transaction().txId(), value); return result;
    }
    private static Hash256 id(int tag) { return Hash256.fromDisplayHex(String.format("%064x", tag)); }
}

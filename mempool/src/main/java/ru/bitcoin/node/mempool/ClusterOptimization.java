package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import java.util.*;

/** Immutable cluster optimization snapshot; safe to share between main and staging. */
final class ClusterOptimization {
    static final long ACCEPTABLE_COST = 75_000;
    static final long POST_CHANGE_COST = 5 * ACCEPTABLE_COST;
    enum Quality { NEEDS_FIX, NEEDS_RELINEARIZE, ACCEPTABLE, OPTIMAL }
    record Model(List<Hash256> ids, Map<Hash256, Integer> positions, SpanningForestLinearizer.Graph graph) { }
    final Model model;
    final List<Hash256> order;
    final List<ClusterLinearization.Chunk> chunks;
    final Quality quality;
    final long cost;

    private ClusterOptimization(Model model, List<Hash256> order, List<ClusterLinearization.Chunk> chunks, Quality quality, long cost) {
        this.model = model; this.order = List.copyOf(order); this.chunks = List.copyOf(chunks); this.quality = quality; this.cost = cost;
    }
    static ClusterOptimization pending(Collection<Hash256> oldOrder) {
        return pending(oldOrder, false);
    }
    static ClusterOptimization pending(Collection<Hash256> oldOrder, boolean needsFix) {
        return new ClusterOptimization(null, List.copyOf(oldOrder), List.of(), needsFix ? Quality.NEEDS_FIX : Quality.NEEDS_RELINEARIZE, 0);
    }
    static Model model(Map<Hash256, MempoolEntry> entries, Set<Hash256> members) {
        var ids = members.stream().sorted(Hash256::compareRawBytes).toList();
        var positions = new HashMap<Hash256, Integer>();
        for (int i = 0; i < ids.size(); i++) positions.put(ids.get(i), i);
        var fees = new long[ids.size()]; var weights = new long[ids.size()]; var parents = new BitSet[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            var entry = Objects.requireNonNull(entries.get(ids.get(i)), "Unknown cluster transaction");
            fees[i] = entry.modifiedFee(); weights[i] = entry.adjustedWeight(); parents[i] = new BitSet();
            for (var input : entry.transaction().inputs()) {
                var parent = positions.get(input.previousOutput().transactionId());
                if (parent != null) parents[i].set(parent);
            }
        }
        return new Model(ids, Map.copyOf(positions), new SpanningForestLinearizer.Graph(fees, weights, parents));
    }
    ClusterOptimization run(Map<Hash256, MempoolEntry> entries, Set<Hash256> members, long budget, long seed) {
        if (members.size() == 1) {
            var id = members.iterator().next(); var order = List.of(id);
            return new ClusterOptimization(null, order, ClusterLinearization.chunks(entries, order), Quality.OPTIMAL, 0);
        }
        var current = model == null ? model(entries, members) : model;
        var old = new ArrayList<Integer>(); var seen = new BitSet();
        for (var id : order) {
            var index = current.positions.get(id);
            if (index != null && !seen.get(index)) { old.add(index); seen.set(index); }
        }
        // Empty old order represents a freshly constructed cluster. Otherwise append
        // added transactions and let Core's topology repair handle late parents.
        if (!old.isEmpty()) for (int i = 0; i < current.ids.size(); i++) if (!seen.get(i)) old.add(i);
        boolean topological = true;
        var done = new BitSet();
        for (int tx : old) {
            var missing = (BitSet) current.graph.parents[tx].clone(); missing.andNot(done);
            if (!missing.isEmpty()) topological = false;
            done.set(tx);
        }
        var result = SpanningForestLinearizer.linearize(current.graph, budget, seed, old, topological, true);
        var nextOrder = result.order().stream().map(current.ids::get).toList();
        var nextQuality = result.optimal() ? Quality.OPTIMAL
                : budget >= ACCEPTABLE_COST || quality == Quality.ACCEPTABLE ? Quality.ACCEPTABLE : Quality.NEEDS_RELINEARIZE;
        return new ClusterOptimization(current, nextOrder, ClusterLinearization.chunks(entries, nextOrder), nextQuality, result.cost());
    }
    static List<Hash256> standalone(Map<Hash256, MempoolEntry> entries, Set<Hash256> members, long budget, long seed, boolean post) {
        var model = model(entries, members);
        return SpanningForestLinearizer.linearize(model.graph, budget, seed, List.of(), true, post)
                .order().stream().map(model.ids::get).toList();
    }
}

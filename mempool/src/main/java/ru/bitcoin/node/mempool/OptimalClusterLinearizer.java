package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;
import java.util.*;

/**
 * Exact maximum-density ancestor-closed sets, followed by minimal-chunk ordering.
 * Core 32.1 uses a budgeted spanning-forest optimizer for the same feerate diagram.
 * This solver uses fractional maximum closure (min-cut), with exact arithmetic.
 * Residual sink components at the optimal rate are the minimal optimal chunks.
 */
final class OptimalClusterLinearizer {
    private final List<Hash256> ids;
    private final long[] fees, weights;
    private final BitSet[] parents;
    private final BitSet remaining;

    private OptimalClusterLinearizer(Map<Hash256, MempoolEntry> entries, Set<Hash256> cluster) {
        ids = cluster.stream().sorted(Comparator.comparing(Hash256::toDisplayHex)).toList();
        int n = ids.size();
        fees = new long[n]; weights = new long[n]; parents = new BitSet[n];
        var positions = new HashMap<Hash256, Integer>();
        for (int i = 0; i < n; i++) positions.put(ids.get(i), i);
        for (int i = 0; i < n; i++) {
            var entry = entries.get(ids.get(i));
            if (entry == null) throw new IllegalArgumentException("Unknown cluster transaction: " + ids.get(i));
            fees[i] = entry.modifiedFee(); weights[i] = entry.adjustedWeight();
            if (weights[i] <= 0) throw new IllegalArgumentException("Non-positive transaction weight");
            parents[i] = new BitSet(n);
            for (var input : entry.transaction().inputs()) {
                var parent = positions.get(input.previousOutput().transactionId());
                if (parent != null) parents[i].set(parent);
            }
        }
        remaining = new BitSet(n); remaining.set(0, n);
        // Reject cycles before invoking maximum closure, which itself permits cycles.
        topological(remaining);
    }

    static List<Hash256> linearize(Map<Hash256, MempoolEntry> entries, Set<Hash256> cluster) {
        var solver = new OptimalClusterLinearizer(entries, cluster);
        var result = new ArrayList<Hash256>(cluster.size());
        while (!solver.remaining.isEmpty()) {
            BitSet chunk = solver.nextChunk();
            for (int index : solver.topological(chunk)) result.add(solver.ids.get(index));
            solver.remaining.andNot(chunk);
        }
        return List.copyOf(result);
    }

    private BitSet nextChunk() {
        long fee = total(fees, remaining), weight = total(weights, remaining);
        while (true) {
            var flow = closure(fee, weight);
            BitSet best = flow.reachableFromSource(); best.and(remaining);
            if (!best.isEmpty()) {
                long nextFee = total(fees, best), nextWeight = total(weights, best);
                if (ClusterLinearization.compareRate(nextFee, nextWeight, fee, weight) > 0) {
                    fee = nextFee; weight = nextWeight;
                    continue;
                }
            }
            // At the optimum, every closed set has non-positive surplus. The
            // residual graph describes every zero-surplus cut, including empty.
            // Sink SCCs that cannot reach the flow sink are the nonempty minimal
            // zero-surplus sets. They are ancestor-closed and have optimal rate.
            BitSet[] reach = flow.transitiveReachability();
            BitSet selected = null;
            long selectedWeight = 0;
            int selectedFallback = 0;
            for (int i = remaining.nextSetBit(0); i >= 0; i = remaining.nextSetBit(i + 1)) {
                if (reach[i].get(flow.sink)) continue;
                var component = new BitSet(ids.size());
                for (int j = remaining.nextSetBit(0); j >= 0; j = remaining.nextSetBit(j + 1))
                    if (reach[i].get(j) && reach[j].get(i)) component.set(j);
                var successors = (BitSet) reach[i].clone();
                successors.and(remaining); successors.andNot(component);
                if (!successors.isEmpty()) continue;
                long size = total(weights, component);
                int fallback = component.length() - 1;
                // Core's optimal minimal chunks: smaller weight first, then
                // least maximum transaction under the supplied fallback order.
                if (selected == null || size < selectedWeight
                        || (size == selectedWeight && fallback < selectedFallback)) {
                    selected = component; selectedWeight = size; selectedFallback = fallback;
                }
            }
            if (selected == null || selected.isEmpty()) throw new IllegalStateException("No optimal cluster chunk");
            return selected;
        }
    }

    private Flow closure(long fee, long weight) {
        int n = ids.size();
        var flow = new Flow(n + 2, n, n + 1);
        var surplus = new BigInteger[n];
        BigInteger positive = BigInteger.ZERO;
        for (int i = remaining.nextSetBit(0); i >= 0; i = remaining.nextSetBit(i + 1)) {
            surplus[i] = BigInteger.valueOf(fees[i]).multiply(BigInteger.valueOf(weight))
                    .subtract(BigInteger.valueOf(weights[i]).multiply(BigInteger.valueOf(fee)));
            if (surplus[i].signum() > 0) positive = positive.add(surplus[i]);
        }
        // Cutting any dependency must cost more than the all-excluded cut.
        BigInteger infinite = positive.add(BigInteger.ONE);
        for (int i = remaining.nextSetBit(0); i >= 0; i = remaining.nextSetBit(i + 1)) {
            if (surplus[i].signum() > 0) flow.add(flow.source, i, surplus[i]);
            else if (surplus[i].signum() < 0) flow.add(i, flow.sink, surplus[i].negate());
            for (int p = parents[i].nextSetBit(0); p >= 0; p = parents[i].nextSetBit(p + 1))
                if (remaining.get(p)) flow.add(i, p, infinite);
        }
        flow.maxFlow(positive);
        return flow;
    }

    private List<Integer> topological(BitSet set) {
        var todo = (BitSet) set.clone();
        var result = new ArrayList<Integer>(set.cardinality());
        while (!todo.isEmpty()) {
            int best = -1;
            for (int i = todo.nextSetBit(0); i >= 0; i = todo.nextSetBit(i + 1)) {
                if (parents[i].intersects(todo)) continue;
                int rate = best < 0 ? 1 : ClusterLinearization.compareRate(fees[i], weights[i], fees[best], weights[best]);
                if (best < 0 || rate > 0 || (rate == 0 && (weights[i] < weights[best]
                        || (weights[i] == weights[best] && i < best)))) best = i;
            }
            if (best < 0) throw new IllegalArgumentException("Cyclic mempool dependencies");
            result.add(best); todo.clear(best);
        }
        return result;
    }

    private static long total(long[] values, BitSet set) {
        long result = 0;
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) result = Math.addExact(result, values[i]);
        return result;
    }

    /** Dinic residual network. Capacities may exceed signed 64-bit cross-products. */
    private static final class Flow {
        private static final class Edge {
            final int to, reverse;
            BigInteger capacity;
            Edge(int to, int reverse, BigInteger capacity) { this.to = to; this.reverse = reverse; this.capacity = capacity; }
        }
        final int source, sink;
        final List<List<Edge>> graph;
        final int[] level, cursor, queue;

        Flow(int count, int source, int sink) {
            this.source = source; this.sink = sink;
            graph = new ArrayList<>(count);
            for (int i = 0; i < count; i++) graph.add(new ArrayList<>());
            level = new int[count]; cursor = new int[count]; queue = new int[count];
        }

        void add(int from, int to, BigInteger capacity) {
            var forward = new Edge(to, graph.get(to).size(), capacity);
            var reverse = new Edge(from, graph.get(from).size(), BigInteger.ZERO);
            graph.get(from).add(forward); graph.get(to).add(reverse);
        }

        boolean levels() {
            Arrays.fill(level, -1); level[source] = 0;
            int head = 0, tail = 0; queue[tail++] = source;
            while (head < tail) {
                int v = queue[head++];
                for (var edge : graph.get(v)) if (edge.capacity.signum() > 0 && level[edge.to] < 0) {
                    level[edge.to] = level[v] + 1; queue[tail++] = edge.to;
                }
            }
            return level[sink] >= 0;
        }

        BigInteger send(int v, BigInteger limit) {
            if (v == sink) return limit;
            for (; cursor[v] < graph.get(v).size(); cursor[v]++) {
                var edge = graph.get(v).get(cursor[v]);
                if (edge.capacity.signum() <= 0 || level[edge.to] != level[v] + 1) continue;
                BigInteger amount = send(edge.to, limit.min(edge.capacity));
                if (amount.signum() == 0) continue;
                edge.capacity = edge.capacity.subtract(amount);
                var reverse = graph.get(edge.to).get(edge.reverse);
                reverse.capacity = reverse.capacity.add(amount);
                return amount;
            }
            return BigInteger.ZERO;
        }

        void maxFlow(BigInteger limit) {
            if (limit.signum() == 0) return;
            while (levels()) {
                Arrays.fill(cursor, 0);
                while (send(source, limit).signum() > 0) { }
            }
        }

        BitSet reachableFromSource() {
            var result = new BitSet(graph.size()); result.set(source);
            int head = 0, tail = 0; queue[tail++] = source;
            while (head < tail) {
                int v = queue[head++];
                for (var edge : graph.get(v)) if (edge.capacity.signum() > 0 && !result.get(edge.to)) {
                    result.set(edge.to); queue[tail++] = edge.to;
                }
            }
            return result;
        }

        BitSet[] transitiveReachability() {
            var reach = new BitSet[graph.size()];
            for (int i = 0; i < reach.length; i++) {
                reach[i] = new BitSet(reach.length); reach[i].set(i);
                for (var edge : graph.get(i)) if (edge.capacity.signum() > 0) reach[i].set(edge.to);
            }
            for (int k = 0; k < reach.length; k++)
                for (var row : reach) if (row.get(k)) row.or(reach[k]);
            return reach;
        }
    }
}

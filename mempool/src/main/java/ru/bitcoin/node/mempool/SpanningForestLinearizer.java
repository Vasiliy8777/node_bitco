/* Copyright (c) The Bitcoin Core developers and bitcoin-node contributors.
 * Distributed under the MIT software license. Algorithm: Bitcoin Core v32.1
 * src/cluster_linearize.h (SpanningForestState, SFLDefaultCostModel, PostLinearize).
 */
package ru.bitcoin.node.mempool;

import java.util.*;

/** Budgeted spanning-forest linearization. Contains metadata only, no transactions. */
final class SpanningForestLinearizer {
    record Result(List<Integer> order, boolean optimal, long cost) {
        Result { order = List.copyOf(order); }
    }

    static final class Graph {
        final int size;
        final long[] fees, weights;
        final BitSet[] ancestors, descendants, parents, children;

        Graph(long[] fees, long[] weights, BitSet[] dependencies) {
            size = fees.length;
            if (weights.length != size || dependencies.length != size) throw new IllegalArgumentException("Graph dimensions");
            this.fees = fees.clone(); this.weights = weights.clone();
            ancestors = sets(size); descendants = sets(size); parents = sets(size); children = sets(size);
            var pending = new BitSet(size); pending.set(0, size);
            for (int i = 0; i < size; i++) {
                if (weights[i] <= 0 || dependencies[i].length() > size || dependencies[i].get(i))
                    throw new IllegalArgumentException("Invalid dependency graph");
                ancestors[i].set(i); descendants[i].set(i);
            }
            while (!pending.isEmpty()) {
                boolean progress = false;
                for (int i : members(pending)) {
                    if (dependencies[i].intersects(pending)) continue;
                    for (int parent : members(dependencies[i])) ancestors[i].or(ancestors[parent]);
                    for (int ancestor : members(ancestors[i])) descendants[ancestor].set(i);
                    pending.clear(i); progress = true;
                }
                if (!progress) throw new IllegalArgumentException("Cyclic mempool dependencies");
            }
            for (int i = 0; i < size; i++) {
                parents[i].or(ancestors[i]); parents[i].clear(i);
                for (int parent : members(parents[i])) if (parents[i].get(parent)) {
                    parents[i].andNot(ancestors[parent]); parents[i].set(parent);
                }
                for (int parent : members(parents[i])) children[parent].set(i);
            }
        }
    }

    /** Core's xoroshiro128++ plus RandomMixin bit buffer and rejection sampling. */
    static final class Rng {
        private long seed, s0, s1, buffer;
        private int buffered;
        Rng(long seed) { this.seed = seed; s0 = splitMix(); s1 = splitMix(); }
        Rng(Rng source) {
            seed = source.seed; s0 = source.s0; s1 = source.s1; buffer = source.buffer; buffered = source.buffered;
        }
        private long splitMix() {
            long z = seed += 0x9e3779b97f4a7c15L;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
        long nextLong() {
            long a = s0, b = s1;
            long result = Long.rotateLeft(a + b, 17) + a;
            b ^= a; s0 = Long.rotateLeft(a, 49) ^ b ^ (b << 21); s1 = Long.rotateLeft(b, 28);
            return result;
        }
        long bits(int count) {
            if (count == 64) return nextLong();
            long result;
            if (count <= buffered) { result = buffer; buffer >>>= count; buffered -= count; }
            else {
                long generated = nextLong(); result = (generated << buffered) | buffer;
                buffer = generated >>> (count - buffered); buffered += 64 - count;
            }
            return result & ((1L << count) - 1);
        }
        boolean bool() { return bits(1) != 0; }
        int range(int range) {
            if (range <= 0) throw new IllegalArgumentException("Empty RNG range");
            int count = 32 - Integer.numberOfLeadingZeros(range - 1), value;
            do { value = (int) bits(count); } while (value >= range);
            return value;
        }
    }

    private static final class SetInfo {
        final BitSet transactions = new BitSet();
        long fee, weight;
        void add(SetInfo other) {
            if (transactions.intersects(other.transactions)) throw new IllegalStateException("Overlapping forest sets");
            transactions.or(other.transactions); fee = Math.addExact(fee, other.fee); weight = Math.addExact(weight, other.weight);
        }
        void subtract(SetInfo other) {
            transactions.andNot(other.transactions); fee = Math.subtractExact(fee, other.fee); weight = Math.subtractExact(weight, other.weight);
        }
    }
    private record Pivot(int chunk, int transaction, int flags) { }
    private final Graph graph;
    private final Rng rng;
    private final SetInfo[] sets;
    private final int[] chunk;
    private final int[][] dependencyTop;
    private final BitSet[] activeChildren, reachableUp, reachableDown;
    private final BitSet chunks = new BitSet(), suboptimal = new BitSet();
    private final ArrayList<Integer> queue = new ArrayList<>();
    private final ArrayDeque<Pivot> nonminimal = new ArrayDeque<>();
    private int queueHead;
    private long cost;

    SpanningForestLinearizer(Graph graph, long seed) {
        this.graph = graph; rng = new Rng(seed);
        int n = graph.size; sets = new SetInfo[n]; chunk = new int[n]; dependencyTop = new int[n][n];
        activeChildren = sets(n); reachableUp = sets(n); reachableDown = sets(n);
        int dependencies = 0;
        for (int i = 0; i < n; i++) {
            sets[i] = new SetInfo(); sets[i].transactions.set(i); sets[i].fee = graph.fees[i]; sets[i].weight = graph.weights[i];
            chunk[i] = i; chunks.set(i);
            reachableUp[i].or(graph.parents[i]); reachableDown[i].or(graph.children[i]);
            dependencies += graph.parents[i].cardinality();
        }
        cost = 87L * n + 4L * dependencies;
    }

    static Result linearize(Graph graph, long budget, long seed, List<Integer> old, boolean topological, boolean postprocess) {
        if (budget < 0) throw new IllegalArgumentException("Negative linearization budget");
        var forest = new SpanningForestLinearizer(graph, seed);
        if (old.isEmpty()) forest.makeTopological();
        else {
            forest.load(old);
            if (!topological) forest.makeTopological();
        }
        if (forest.cost < budget) {
            forest.startOptimizing();
            do { if (!forest.optimizeStep()) break; } while (forest.cost < budget);
        }
        boolean optimal = false;
        if (forest.cost < budget) {
            forest.startMinimizing();
            do { if (!forest.minimizeStep()) { optimal = true; break; } } while (forest.cost < budget);
        }
        var order = forest.order();
        if (postprocess) order = postLinearize(graph, order);
        return new Result(order, optimal, forest.cost);
    }

    private int activate(int parent, int child) {
        int topIndex = chunk[parent], bottomIndex = chunk[child];
        var top = sets[topIndex]; var bottom = sets[bottomIndex];
        for (int tx : members(top.transactions)) {
            chunk[tx] = bottomIndex;
            for (int next : members(activeChildren[tx])) {
                var dependency = sets[dependencyTop[tx][next]];
                if (dependency.transactions.get(parent)) dependency.add(bottom);
            }
        }
        for (int tx : members(bottom.transactions))
            for (int next : members(activeChildren[tx])) {
                var dependency = sets[dependencyTop[tx][next]];
                if (dependency.transactions.get(child)) dependency.add(top);
            }
        bottom.add(top);
        reachableUp[bottomIndex].or(reachableUp[topIndex]); reachableUp[bottomIndex].andNot(bottom.transactions);
        reachableDown[bottomIndex].or(reachableDown[topIndex]); reachableDown[bottomIndex].andNot(bottom.transactions);
        dependencyTop[parent][child] = topIndex; activeChildren[parent].set(child); chunks.clear(topIndex);
        cost += 10L * (bottom.transactions.cardinality() - 1) + 1;
        return bottomIndex;
    }

    private int[] deactivate(int parent, int child) {
        int topIndex = dependencyTop[parent][child], bottomIndex = chunk[parent];
        var top = sets[topIndex]; var bottom = sets[bottomIndex];
        activeChildren[parent].clear(child); chunks.set(topIndex);
        int count = bottom.transactions.cardinality(); bottom.subtract(top);
        var topParents = new BitSet(); var topChildren = new BitSet();
        for (int tx : members(top.transactions)) {
            chunk[tx] = topIndex; topParents.or(graph.parents[tx]); topChildren.or(graph.children[tx]);
            for (int next : members(activeChildren[tx])) {
                var dependency = sets[dependencyTop[tx][next]];
                if (dependency.transactions.get(parent)) dependency.subtract(bottom);
            }
        }
        var bottomParents = new BitSet(); var bottomChildren = new BitSet();
        for (int tx : members(bottom.transactions)) {
            bottomParents.or(graph.parents[tx]); bottomChildren.or(graph.children[tx]);
            for (int next : members(activeChildren[tx])) {
                var dependency = sets[dependencyTop[tx][next]];
                if (dependency.transactions.get(child)) dependency.subtract(top);
            }
        }
        topParents.andNot(top.transactions); topChildren.andNot(top.transactions);
        bottomParents.andNot(bottom.transactions); bottomChildren.andNot(bottom.transactions);
        reachableUp[topIndex] = topParents; reachableDown[topIndex] = topChildren;
        reachableUp[bottomIndex] = bottomParents; reachableDown[bottomIndex] = bottomChildren;
        cost += 11L * (count - 1) + 8;
        return new int[]{topIndex, bottomIndex};
    }

    private int merge(int top, int bottom) {
        int[] counts = new int[graph.size]; int dependencies = 0;
        for (int tx : members(sets[top].transactions)) {
            counts[tx] = intersection(graph.children[tx], sets[bottom].transactions).cardinality(); dependencies += counts[tx];
        }
        cost += 2L * sets[top].transactions.cardinality();
        int pick = rng.range(dependencies), steps = 0;
        for (int tx : members(sets[top].transactions)) {
            steps++;
            if (pick < counts[tx]) {
                for (int child : members(intersection(graph.children[tx], sets[bottom].transactions))) {
                    if (pick-- == 0) { cost += 3L * steps + 5; return activate(tx, child); }
                }
                throw new IllegalStateException("Missing merge edge");
            }
            pick -= counts[tx];
        }
        throw new IllegalStateException("Disconnected merge");
    }

    private int mergeStep(int index, boolean down) {
        var todo = copy(down ? reachableDown[index] : reachableUp[index]);
        int best = -1, steps = 0; long tie = 0; var rate = sets[index];
        while (!todo.isEmpty()) {
            steps++; int other = chunk[todo.nextSetBit(0)]; todo.andNot(sets[other].transactions);
            int comparison = down ? compare(rate, sets[other]) : compare(sets[other], rate);
            if (comparison > 0) continue;
            long candidate = rng.nextLong();
            if (comparison < 0 || Long.compareUnsigned(candidate, tie) >= 0) {
                rate = sets[other]; best = other; tie = candidate;
            }
        }
        cost += 8L * steps;
        return best < 0 ? -1 : down ? merge(index, best) : merge(best, index);
    }

    private void enqueue(int index) {
        if (!suboptimal.get(index)) { suboptimal.set(index); queue.add(index); }
    }
    private void mergeSequence(int index, boolean down) {
        while (true) { int next = mergeStep(index, down); if (next < 0) break; index = next; }
        enqueue(index);
    }
    private void improve(int parent, int child) {
        int[] split = deactivate(parent, child);
        if (reachableUp[split[0]].intersects(sets[split[1]].transactions)) enqueue(merge(split[1], split[0]));
        else { mergeSequence(split[0], false); mergeSequence(split[1], true); }
    }
    private void randomQueue() {
        queue.clear(); queueHead = 0; suboptimal.clear(); suboptimal.or(chunks);
        for (int index : members(chunks)) { queue.add(index); Collections.swap(queue, queue.size() - 1, rng.range(queue.size())); }
    }
    private void load(List<Integer> old) {
        if (old.size() != graph.size || new HashSet<>(old).size() != graph.size
                || old.stream().anyMatch(i -> i < 0 || i >= graph.size)) throw new IllegalArgumentException("Invalid old linearization");
        for (int tx : old) {
            int index = chunk[tx];
            while (true) { index = mergeStep(index, false); if (index < 0) break; }
        }
    }
    private void makeTopological() {
        int initialDirection = rng.bool() ? 1 : 0; var merged = new BitSet(); randomQueue();
        int originalChunks = chunks.cardinality(), steps = 0;
        while (queueHead < queue.size()) {
            steps++; int index = queue.get(queueHead++); suboptimal.clear(index);
            if (!chunks.get(index)) continue;
            int direction = merged.get(index) ? 3 : initialDirection + 1; int flip = rng.bool() ? 1 : 0;
            for (int i = 0; i < 2; i++) {
                boolean down = (i ^ flip) == 0;
                if ((direction & (down ? 2 : 1)) == 0) continue;
                int next = mergeStep(index, down);
                if (next >= 0) { enqueue(next); merged.set(next); break; }
            }
        }
        queue.clear(); queueHead = 0;
        cost += 20L * originalChunks + 28L * steps;
    }
    private void startOptimizing() { randomQueue(); cost += 13L * queue.size(); }
    private boolean optimizeStep() {
        int index = -1, steps = 0;
        while (queueHead < queue.size()) {
            steps++; int candidate = queue.get(queueHead++); suboptimal.clear(candidate);
            if (chunks.get(candidate)) { index = candidate; break; }
        }
        cost += steps + 4L;
        if (index < 0) return false;
        int parent = -1, child = -1; long tie = 0;
        for (int tx : members(sets[index].transactions))
            for (int next : members(activeChildren[tx])) {
                if (compare(sets[dependencyTop[tx][next]], sets[index]) <= 0) continue;
                long candidate = rng.nextLong();
                if (Long.compareUnsigned(candidate, tie) < 0) continue;
                parent = tx; child = next; tie = candidate;
            }
        cost += 8L * sets[index].transactions.cardinality() + 9;
        if (parent < 0) return queueHead < queue.size();
        improve(parent, child); return true;
    }
    private int pick(BitSet transactions) { return members(transactions)[rng.range(transactions.cardinality())]; }
    private void startMinimizing() {
        var shuffled = new ArrayList<Pivot>();
        for (int index : members(chunks)) {
            shuffled.add(new Pivot(index, pick(sets[index].transactions), (int) rng.bits(1)));
            Collections.swap(shuffled, shuffled.size() - 1, rng.range(shuffled.size()));
        }
        nonminimal.clear(); nonminimal.addAll(shuffled); cost += 18L * shuffled.size();
    }
    private boolean minimizeStep() {
        if (nonminimal.isEmpty()) return false;
        var pivot = nonminimal.removeFirst(); int index = pivot.chunk, flags = pivot.flags;
        boolean down = (flags & 1) != 0, second = (flags & 2) != 0, haveAny = false;
        int parent = -1, child = -1; long tie = 0;
        for (int tx : members(sets[index].transactions))
            for (int next : members(activeChildren[tx])) {
                var top = sets[dependencyTop[tx][next]];
                if (compare(top, sets[index]) < 0) continue;
                haveAny = true;
                if (down == top.transactions.get(pivot.transaction)) continue;
                long candidate = rng.nextLong() | 1;
                if (Long.compareUnsigned(candidate, tie) > 0) { tie = candidate; parent = tx; child = next; }
            }
        cost += 11L * sets[index].transactions.cardinality() + 11;
        if (!haveAny) return true;
        if (tie == 0) {
            if (!second) nonminimal.addLast(new Pivot(index, pivot.transaction, flags ^ 3));
            return true;
        }
        int[] split = deactivate(parent, child);
        if (reachableUp[split[0]].intersects(sets[split[1]].transactions)) {
            nonminimal.addLast(new Pivot(merge(split[1], split[0]), pivot.transaction, flags)); cost += 7;
        } else {
            Pivot first, last;
            if (down) {
                first = new Pivot(split[0], pick(sets[split[0]].transactions), (int) rng.bits(1));
                last = new Pivot(split[1], pivot.transaction, flags);
            } else {
                first = new Pivot(split[0], pivot.transaction, flags);
                last = new Pivot(split[1], pick(sets[split[1]].transactions), (int) rng.bits(1));
            }
            if (rng.bool()) { nonminimal.addLast(last); nonminimal.addLast(first); }
            else { nonminimal.addLast(first); nonminimal.addLast(last); }
            cost += 24;
        }
        return true;
    }

    private List<Integer> order() {
        int n = graph.size; int[] chunkDependencies = new int[n], transactionDependencies = new int[n];
        for (int tx = 0; tx < n; tx++) {
            transactionDependencies[tx] = graph.parents[tx].cardinality();
            var external = copy(graph.parents[tx]); external.andNot(sets[chunk[tx]].transactions);
            chunkDependencies[chunk[tx]] += external.cardinality();
        }
        Comparator<Integer> transactionOrder = (a, b) -> {
            int rate = ClusterLinearization.compareRate(graph.fees[b], graph.weights[b], graph.fees[a], graph.weights[a]);
            if (rate != 0) return rate;
            int weight = Long.compare(graph.weights[a], graph.weights[b]); return weight != 0 ? weight : Integer.compare(a, b);
        };
        Comparator<Integer> chunkOrder = (a, b) -> {
            int rate = compare(sets[b], sets[a]); if (rate != 0) return rate;
            int weight = Long.compare(sets[a].weight, sets[b].weight);
            return weight != 0 ? weight : Integer.compare(sets[a].transactions.length(), sets[b].transactions.length());
        };
        var readyChunks = new PriorityQueue<>(chunkOrder); var ready = new PriorityQueue<>(transactionOrder);
        for (int index : members(chunks)) if (chunkDependencies[index] == 0) readyChunks.add(index);
        var result = new ArrayList<Integer>(n);
        while (!readyChunks.isEmpty()) {
            int index = readyChunks.remove(); var transactions = sets[index].transactions;
            for (int tx : members(transactions)) if (transactionDependencies[tx] == 0) ready.add(tx);
            while (!ready.isEmpty()) {
                int tx = ready.remove(); result.add(tx);
                for (int child : members(graph.children[tx])) {
                    if (--transactionDependencies[child] == 0 && transactions.get(child)) ready.add(child);
                    if (chunk[child] != index && --chunkDependencies[chunk[child]] == 0) readyChunks.add(chunk[child]);
                }
            }
        }
        if (result.size() != n) throw new IllegalStateException("Non-topological forest");
        return List.copyOf(result);
    }

    private static final class PostEntry {
        int previousTransaction, firstTransaction, previousGroup;
        BitSet group, dependencies;
        long fee, weight;
    }
    static List<Integer> postLinearize(Graph graph, List<Integer> input) {
        var order = new ArrayList<>(input); var entries = new PostEntry[graph.size + 1];
        for (int i = 0; i < entries.length; i++) entries[i] = new PostEntry();
        for (int pass = 0; pass < 2; pass++) {
            boolean reverse = pass == 0; entries[0].previousGroup = 0;
            for (int i = 0; i < order.size(); i++) {
                int tx = order.get(reverse ? order.size() - 1 - i : i), current = tx + 1;
                var entry = entries[current]; entry.group = new BitSet(); entry.group.set(tx);
                entry.dependencies = copy(reverse ? graph.descendants[tx] : graph.ancestors[tx]);
                entry.fee = reverse ? Math.negateExact(graph.fees[tx]) : graph.fees[tx]; entry.weight = graph.weights[tx];
                entry.previousTransaction = 0; entry.firstTransaction = current;
                entry.previousGroup = entries[0].previousGroup; entries[0].previousGroup = current;
                int next = 0, previous = entry.previousGroup;
                while (previous != 0 && ClusterLinearization.compareRate(entry.fee, entry.weight, entries[previous].fee, entries[previous].weight) > 0) {
                    var before = entries[previous];
                    if (entry.dependencies.intersects(before.group)) {
                        entry.group.or(before.group); entry.dependencies.or(before.dependencies);
                        entry.fee = Math.addExact(entry.fee, before.fee); entry.weight = Math.addExact(entry.weight, before.weight);
                        entries[entry.firstTransaction].previousTransaction = previous; entry.firstTransaction = before.firstTransaction;
                        previous = before.previousGroup; entry.previousGroup = previous;
                    } else {
                        int earlier = before.previousGroup;
                        entries[next].previousGroup = previous; before.previousGroup = current; entry.previousGroup = earlier;
                        next = previous; previous = earlier;
                    }
                }
            }
            int current = entries[0].previousGroup, done = 0;
            while (current != 0) {
                int tx = current;
                do { order.set(reverse ? done++ : order.size() - ++done, tx - 1); tx = entries[tx].previousTransaction; } while (tx != 0);
                current = entries[current].previousGroup;
            }
            if (done != graph.size) throw new IllegalStateException("Incomplete postlinearization");
        }
        return List.copyOf(order);
    }

    private static int compare(SetInfo a, SetInfo b) { return ClusterLinearization.compareRate(a.fee, a.weight, b.fee, b.weight); }
    private static BitSet[] sets(int count) { var result = new BitSet[count]; Arrays.setAll(result, i -> new BitSet(count)); return result; }
    private static BitSet copy(BitSet value) { return (BitSet) value.clone(); }
    private static BitSet intersection(BitSet a, BitSet b) { var result = copy(a); result.and(b); return result; }
    private static int[] members(BitSet value) { return value.stream().toArray(); }
}

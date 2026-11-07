package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;
import java.util.*;

/**
 * Deterministic dependency-respecting cluster linearization and feerate diagram.
 * <p>
 * The linearization repeatedly appends a minimal highest-feerate ancestor-closed
 * set, including sets shared by multiple descendants. Chunks are the maximum-feerate
 * prefixes of the remaining linearization, yielding a monotonically non-increasing
 * feerate diagram.
 * Arithmetic comparisons are exact; no floating point is used.
 */
public final class ClusterLinearization {
    private ClusterLinearization() {
    }

    public record Chunk(List<Hash256> transactions, long fee, long virtualSize, long adjustedWeight) {
        public Chunk {
            transactions = List.copyOf(transactions);
            if (transactions.isEmpty()) throw new IllegalArgumentException("chunk must not be empty");
            if (virtualSize <= 0 || adjustedWeight <= 0) throw new IllegalArgumentException("invalid chunk totals");
        }
    }

    public static List<Set<Hash256>> clusters(Map<Hash256, MempoolEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Set<Hash256> remaining = new LinkedHashSet<>(entries.keySet());
        var neighbors = neighbors(entries);
        List<Set<Hash256>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Hash256 root = remaining.iterator().next();
            Set<Hash256> component = connectedNeighbors(neighbors, List.of(root));
            remaining.removeAll(component);
            result.add(component);
        }
        return List.copyOf(result);
    }

    public static List<Hash256> linearize(Map<Hash256, MempoolEntry> entries, Set<Hash256> cluster) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(cluster, "cluster");
        if (cluster.isEmpty()) return List.of();
        if (cluster.size() == 1) {
            var id = cluster.iterator().next();
            var entry = require(entries, id);
            if (entry.adjustedWeight() <= 0) throw new IllegalArgumentException("Non-positive transaction weight");
            if (entry.transaction().inputs().stream().anyMatch(input -> input.previousOutput().transactionId().equals(id)))
                throw new IllegalArgumentException("Cyclic mempool dependencies");
            return List.of(id);
        }
        return OptimalClusterLinearizer.linearize(entries, cluster);
    }

    public static List<Chunk> chunks(Map<Hash256, MempoolEntry> entries, Set<Hash256> cluster) {
        return chunks(entries, linearize(entries, cluster));
    }

    public static List<Chunk> chunks(Map<Hash256, MempoolEntry> entries, List<Hash256> linearization) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(linearization, "linearization");
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < linearization.size()) {
            long fee = 0, size = 0, vsize = 0, bestFee = 0, bestSize = 1, bestVsize = 0;
            int bestEnd = -1;
            for (int i = start; i < linearization.size(); i++) {
                MempoolEntry entry = require(entries, linearization.get(i));
                fee = Math.addExact(fee, entry.modifiedFee());
                size = Math.addExact(size, entry.adjustedWeight());
                vsize = Math.addExact(vsize, entry.virtualSize());
                if (bestEnd < 0 || compareRate(fee, size, bestFee, bestSize) > 0) {
                    bestEnd = i + 1;
                    bestFee = fee;
                    bestSize = size;
                    bestVsize = vsize;
                }
            }
            result.add(new Chunk(linearization.subList(start, bestEnd), bestFee, bestVsize, bestSize));
            start = bestEnd;
        }
        return List.copyOf(result);
    }

    /**
     * Returns the whole-mempool feerate diagram by merging every cluster's chunks
     * in descending feerate order, preserving chunk order within each cluster.
     * Equal-feerate chunks may still depend on earlier chunks in that cluster.
     */
    public static List<Chunk> mempoolDiagram(Map<Hash256, MempoolEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        return mergeChunks(clusters(entries).stream().map(cluster -> chunks(entries, cluster)).toList());
    }

    /** Merge already linearized clusters without losing intra-cluster dependencies. */
    public static List<Chunk> mergeChunks(List<List<Chunk>> clusters) {
        List<Chunk> result = new ArrayList<>();
        record Cursor(List<Chunk> chunks, int position) {
            Chunk head() { return chunks.get(position); }
        }
        var queue = new PriorityQueue<Cursor>((left, right) -> {
            var a = left.head(); var b = right.head();
            int rate = compareRate(b.fee(), b.adjustedWeight(), a.fee(), a.adjustedWeight());
            if (rate != 0) return rate;
            return comparePackage(a.transactions(), b.transactions());
        });
        for (var cluster : clusters) if (!cluster.isEmpty()) queue.add(new Cursor(cluster, 0));
        while (!queue.isEmpty()) {
            var cursor = queue.remove(); result.add(cursor.head());
            if (cursor.position() + 1 < cursor.chunks().size())
                queue.add(new Cursor(cursor.chunks(), cursor.position() + 1));
        }
        return List.copyOf(result);
    }

    /**
     * Returns negative/zero/positive when left diagram is worse/equal/better than right.
     */
    public static int compareDiagrams(List<Chunk> left, List<Chunk> right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        TreeSet<Long> points = new TreeSet<>();
        addBreakpoints(left, points);
        addBreakpoints(right, points);
        var leftCursor = new DiagramCursor(left);
        var rightCursor = new DiagramCursor(right);
        int result = 0;
        for (long x : points) {
            Rational l = leftCursor.valueAt(x), r = rightCursor.valueAt(x);
            int cmp = l.numerator.multiply(r.denominator).compareTo(r.numerator.multiply(l.denominator));
            if (cmp < 0) return -1;
            if (cmp > 0) result = 1;
        }
        return result;
    }

    public static Set<Hash256> connected(Map<Hash256, MempoolEntry> entries, Collection<Hash256> roots) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(roots);
        return connectedNeighbors(neighbors(entries), roots);
    }

    /** Build adjacency once and stop as soon as the conflicting-component bound is exceeded. */
    static int countConflictingClusters(Map<Hash256, MempoolEntry> entries, Set<Hash256> roots, int maximum) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(roots);
        if (maximum < 0) throw new IllegalArgumentException("Negative cluster bound");
        if (roots.isEmpty()) return 0;
        var graph = neighbors(entries);
        var counted = new HashSet<Hash256>();
        int count = 0;
        for (var root : roots) {
            if (counted.contains(root)) continue;
            if (++count > maximum) return count;
            counted.addAll(connectedNeighbors(graph, List.of(root)));
        }
        return count;
    }

    /** Build both directions once, rather than scanning every transaction per visited node. */
    private static Map<Hash256, List<Hash256>> neighbors(Map<Hash256, MempoolEntry> entries) {
        Map<Hash256, List<Hash256>> neighbors = new LinkedHashMap<>();
        for (Hash256 id : entries.keySet()) neighbors.put(id, new ArrayList<>());
        for (var entry : entries.entrySet()) {
            Hash256 child = entry.getKey();
            for (var input : entry.getValue().transaction().inputs()) {
                Hash256 parent = input.previousOutput().transactionId();
                if (neighbors.containsKey(parent)) {
                    neighbors.get(child).add(parent);
                    neighbors.get(parent).add(child);
                }
            }
        }
        return neighbors;
    }

    private static Set<Hash256> connectedNeighbors(Map<Hash256, List<Hash256>> neighbors, Collection<Hash256> roots) {
        Set<Hash256> result = new LinkedHashSet<>();
        Deque<Hash256> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Hash256 id = queue.removeFirst();
            if (!neighbors.containsKey(id) || !result.add(id)) continue;
            for (Hash256 neighbor : neighbors.get(id)) if (!result.contains(neighbor)) queue.addLast(neighbor);
        }
        return Set.copyOf(result);
    }

    private static int comparePackage(Collection<Hash256> a, Collection<Hash256> b) {
        Iterator<Hash256> ai = a.iterator(), bi = b.iterator();
        while (ai.hasNext() && bi.hasNext()) {
            int c = ai.next().toDisplayHex().compareTo(bi.next().toDisplayHex());
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static MempoolEntry require(Map<Hash256, MempoolEntry> entries, Hash256 id) {
        MempoolEntry e = entries.get(id);
        if (e == null) throw new IllegalArgumentException("Unknown transaction: " + id);
        return e;
    }

    public static int compareRate(long fa, long sa, long fb, long sb) {
        if (sa <= 0 || sb <= 0) throw new IllegalArgumentException("size must be positive");
        return BigInteger.valueOf(fa).multiply(BigInteger.valueOf(sb)).compareTo(BigInteger.valueOf(fb).multiply(BigInteger.valueOf(sa)));
    }

    private static void addBreakpoints(List<Chunk> chunks, Set<Long> points) {
        long x = 0;
        for (Chunk c : chunks) {
            x = Math.addExact(x, c.adjustedWeight());
            points.add(x);
        }
    }

    private record Rational(BigInteger numerator, BigInteger denominator) {
    }

    /** Breakpoints are ascending: consume each segment once instead of rescanning
     * the complete diagram at every point while holding the chainstate lock. */
    private static final class DiagramCursor {
        private final Iterator<Chunk> chunks;
        private Chunk current;
        private long weight;
        private BigInteger fee = BigInteger.ZERO;

        DiagramCursor(List<Chunk> chunks) {
            this.chunks = chunks.iterator();
            advance();
        }

        private void advance() { current = chunks.hasNext() ? chunks.next() : null; }

        Rational valueAt(long x) {
            while (current != null && x - weight >= current.adjustedWeight()) {
                weight = Math.addExact(weight, current.adjustedWeight());
                fee = fee.add(BigInteger.valueOf(current.fee()));
                advance();
            }
            if (current == null || x == weight) return new Rational(fee, BigInteger.ONE);
            var denominator = BigInteger.valueOf(current.adjustedWeight());
            return new Rational(fee.multiply(denominator).add(BigInteger.valueOf(current.fee())
                    .multiply(BigInteger.valueOf(x - weight))), denominator);
        }
    }
}

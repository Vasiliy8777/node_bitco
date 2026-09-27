package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;
import java.util.*;

/**
 * Deterministic dependency-respecting cluster linearization and feerate diagram.
 * <p>
 * The linearization repeatedly appends the highest-feerate ancestor package that
 * remains in the cluster. Chunks are the maximum-feerate prefixes of the remaining
 * linearization, yielding a monotonically non-increasing feerate diagram.
 * Arithmetic comparisons are exact; no floating point is used.
 */
public final class ClusterLinearization {
    private ClusterLinearization() {
    }

    public record Chunk(List<Hash256> transactions, long fee, long virtualSize, long adjustedWeight) {
        public Chunk {
            transactions = List.copyOf(transactions);
            if (transactions.isEmpty()) throw new IllegalArgumentException("chunk must not be empty");
            if (fee < 0 || virtualSize <= 0 || adjustedWeight <= 0) throw new IllegalArgumentException("invalid chunk totals");
        }
    }

    public static List<Set<Hash256>> clusters(Map<Hash256, MempoolEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Set<Hash256> remaining = new LinkedHashSet<>(entries.keySet());
        List<Set<Hash256>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Hash256 root = remaining.iterator().next();
            Set<Hash256> component = connected(entries, root);
            remaining.removeAll(component);
            result.add(component);
        }
        return List.copyOf(result);
    }

    public static List<Hash256> linearize(Map<Hash256, MempoolEntry> entries, Set<Hash256> cluster) {
        Objects.requireNonNull(entries, "entries");
        Set<Hash256> remaining = new LinkedHashSet<>(Objects.requireNonNull(cluster, "cluster"));
        if (remaining.isEmpty()) return List.of();
        for (Hash256 id : remaining)
            if (!entries.containsKey(id)) throw new IllegalArgumentException("Unknown cluster transaction: " + id);
        List<Hash256> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            List<Hash256> best = null;
            long bestFee = 0, bestSize = 1;
            for (Hash256 id : remaining) {
                LinkedHashSet<Hash256> packageIds = new LinkedHashSet<>();
                collectAncestors(id, entries, remaining, new HashSet<>(), packageIds);
                long fee = fee(entries, packageIds), size = adjustedWeight(entries, packageIds);
                if (best == null || compareRate(fee, size, bestFee, bestSize) > 0
                        || (compareRate(fee, size, bestFee, bestSize) == 0 && comparePackage(packageIds, best) < 0)) {
                    best = List.copyOf(packageIds);
                    bestFee = fee;
                    bestSize = size;
                }
            }
            if (best == null || best.isEmpty()) throw new IllegalStateException("Unable to linearize cluster");
            result.addAll(best);
            remaining.removeAll(best);
        }
        return List.copyOf(result);
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
                fee = Math.addExact(fee, entry.fee());
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
     * in descending feerate order. Cluster chunks are independently dependency-safe,
     * so interleaving clusters by chunk feerate preserves a valid mining order.
     */
    public static List<Chunk> mempoolDiagram(Map<Hash256, MempoolEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Chunk> result = new ArrayList<>();
        for (Set<Hash256> cluster : clusters(entries)) result.addAll(chunks(entries, cluster));
        result.sort((a, b) -> {
            int rate = compareRate(b.fee(), b.adjustedWeight(), a.fee(), a.adjustedWeight());
            if (rate != 0) return rate;
            return comparePackage(a.transactions(), b.transactions());
        });
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
        int result = 0;
        for (long x : points) {
            Rational l = valueAt(left, x), r = valueAt(right, x);
            int cmp = l.numerator.multiply(r.denominator).compareTo(r.numerator.multiply(l.denominator));
            if (cmp < 0) return -1;
            if (cmp > 0) result = 1;
        }
        return result;
    }

    public static Set<Hash256> connected(Map<Hash256, MempoolEntry> entries, Collection<Hash256> roots) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(roots);
        Set<Hash256> result = new LinkedHashSet<>();
        Deque<Hash256> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Hash256 id = queue.removeFirst();
            if (!entries.containsKey(id) || !result.add(id)) continue;
            for (var input : entries.get(id).transaction().inputs()) {
                Hash256 parent = input.previousOutput().transactionId();
                if (entries.containsKey(parent)) queue.addLast(parent);
            }
            for (var candidate : entries.entrySet()) {
                if (!result.contains(candidate.getKey()) && candidate.getValue().transaction().inputs().stream()
                        .anyMatch(input -> input.previousOutput().transactionId().equals(id)))
                    queue.addLast(candidate.getKey());
            }
        }
        return Set.copyOf(result);
    }

    private static Set<Hash256> connected(Map<Hash256, MempoolEntry> entries, Hash256 root) {
        return connected(entries, List.of(root));
    }

    private static void collectAncestors(Hash256 id, Map<Hash256, MempoolEntry> entries, Set<Hash256> remaining,
                                         Set<Hash256> visiting, LinkedHashSet<Hash256> result) {
        if (!remaining.contains(id) || result.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic mempool dependencies");
        for (var input : require(entries, id).transaction().inputs())
            collectAncestors(input.previousOutput().transactionId(), entries, remaining, visiting, result);
        visiting.remove(id);
        result.add(id);
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

    private static long fee(Map<Hash256, MempoolEntry> entries, Collection<Hash256> ids) {
        long v = 0;
        for (Hash256 id : ids) v = Math.addExact(v, require(entries, id).fee());
        return v;
    }

    private static long size(Map<Hash256, MempoolEntry> entries, Collection<Hash256> ids) {
        long v = 0;
        for (Hash256 id : ids) v = Math.addExact(v, require(entries, id).virtualSize());
        return v;
    }

    private static long adjustedWeight(Map<Hash256, MempoolEntry> entries, Collection<Hash256> ids) {
        long v = 0;
        for (Hash256 id : ids) v = Math.addExact(v, require(entries, id).adjustedWeight());
        return v;
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

    private static Rational valueAt(List<Chunk> chunks, long x) {
        BigInteger fee = BigInteger.ZERO;
        long remaining = x;
        for (Chunk chunk : chunks) {
            if (remaining <= 0) break;
            if (remaining < chunk.adjustedWeight())
                return new Rational(fee.multiply(BigInteger.valueOf(chunk.adjustedWeight())).add(BigInteger.valueOf(chunk.fee()).multiply(BigInteger.valueOf(remaining))), BigInteger.valueOf(chunk.adjustedWeight()));
            fee = fee.add(BigInteger.valueOf(chunk.fee()));
            remaining -= chunk.adjustedWeight();
        }
        return new Rational(fee, BigInteger.ONE);
    }
}

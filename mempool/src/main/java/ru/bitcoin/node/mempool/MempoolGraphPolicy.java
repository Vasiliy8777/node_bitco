package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.math.BigInteger;
import java.util.*;

/**
 * Dependency, replacement and eviction checks on an isolated prospective pool.
 */
final class MempoolGraphPolicy {
    private MempoolGraphPolicy() {
    }

    static Set<Hash256> descendants(Map<Hash256, MempoolEntry> entries, Set<Hash256> roots) {
        if (roots.isEmpty()) return Set.of();
        // Build child edges once. Repeated whole-pool scans depend on insertion
        // order and become quadratic for reverse-ordered chains after reorg.
        Map<Hash256, List<Hash256>> children = new HashMap<>();
        for (var entry : entries.entrySet()) {
            for (var input : entry.getValue().transaction().inputs())
                children.computeIfAbsent(input.previousOutput().transactionId(), ignored -> new ArrayList<>())
                        .add(entry.getKey());
        }
        Set<Hash256> result = new HashSet<>(roots);
        Deque<Hash256> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            for (Hash256 child : children.getOrDefault(pending.removeFirst(), List.of()))
                if (result.add(child)) pending.addLast(child);
        }
        return result;
    }

    static Set<Hash256> ancestors(Map<Hash256, MempoolEntry> entries, Hash256 id) {
        Set<Hash256> result = new HashSet<>();
        Deque<Hash256> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            Hash256 current = queue.removeFirst();
            if (!entries.containsKey(current) || !result.add(current)) continue;
            for (var in : entries.get(current).transaction().inputs()) queue.add(in.previousOutput().transactionId());
        }
        return result;
    }

    static void replacement(Map<Hash256, MempoolEntry> entries, Set<Hash256> conflicts,
                            Set<Hash256> evicted, MempoolEntry replacement, MempoolLimits limits) {
        if (conflicts.isEmpty()) return;

        // Core v31 Rule #5 limits directly conflicting *clusters*, not the number of
        // transactions/descendants removed by the replacement.
        checkConflictClusterLimit(entries, conflicts, "too-many-conflicting-clusters");

        long oldFee = 0;
        for (Hash256 id : evicted) oldFee = Math.addExact(oldFee, entries.get(id).modifiedFee());
        long delta = new FeeRate(limits.incrementalRelaySatPerKvB()).feeForVSize(replacement.virtualSize());
        if (replacement.modifiedFee() < oldFee || replacement.modifiedFee() - oldFee < delta) fail("replacement-fee");

        // Core v31 removed BIP125 rules #1/#2 and the old per-direct-conflict
        // feerate rule. The prospective mempool must instead have a strictly
        // superior feerate diagram.
        Map<Hash256, MempoolEntry> after = new LinkedHashMap<>(entries);
        evicted.forEach(after::remove);
        after.put(replacement.transaction().txId(), replacement);
        // Core's CalculateChunksForRBF checks prospective graph limits before
        // optimizing any chunks. Do not run the solver on an oversized cluster.
        checkClusterLimits(after, replacement.transaction().txId(), limits);
        int diagram = compareReplacementDiagrams(entries, after, conflicts, replacement);
        if (diagram < 0) fail("replacement-worse-feerate-diagram");
        if (diagram == 0) fail("replacement-unchanged-feerate-diagram");
    }

    /** Include old parents' clusters as well as conflicts, and every component
     * left after removals split an affected cluster. Unchanged clusters cancel. */
    static int compareReplacementDiagrams(Map<Hash256, MempoolEntry> before,
                                          Map<Hash256, MempoolEntry> after,
                                          Set<Hash256> conflicts, MempoolEntry replacement) {
        var roots = new HashSet<>(conflicts);
        for (var input : replacement.transaction().inputs()) roots.add(input.previousOutput().transactionId());
        var oldIds = ClusterLinearization.connected(before, roots);
        var newRoots = new HashSet<>(oldIds);
        newRoots.add(replacement.transaction().txId());
        var newIds = ClusterLinearization.connected(after, newRoots);
        var oldAffected = new LinkedHashMap<Hash256, MempoolEntry>();
        var newAffected = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var id : oldIds) oldAffected.put(id, before.get(id));
        for (var id : newIds) newAffected.put(id, after.get(id));
        return ClusterLinearization.compareDiagrams(ClusterLinearization.mempoolDiagram(newAffected),
                ClusterLinearization.mempoolDiagram(oldAffected));
    }

    /** Shared Core GetEntriesForConflicts bound for single and package RBF. */
    static void checkConflictClusterLimit(Map<Hash256, MempoolEntry> entries,
                                          Set<Hash256> conflicts, String reason) {
        if (ClusterLinearization.countConflictingClusters(entries, conflicts, 100) > 100) fail(reason);
    }

    static Set<Hash256> connected(Map<Hash256, MempoolEntry> entries, Hash256 root) {
        return ClusterLinearization.connected(entries, List.of(root));
    }

    /** Prospective component for a single conflict-free addition. The existing
     * spent-outpoint index supplies child edges without scanning unrelated pools. */
    static Map<Hash256, MempoolEntry> additionCluster(Map<Hash256, MempoolEntry> entries,
            Map<OutPoint, Hash256> spent, MempoolEntry added, int maximumCount) {
        Hash256 root = added.transaction().txId();
        var cluster = new LinkedHashMap<Hash256, MempoolEntry>();
        var pending = new ArrayDeque<Hash256>(); pending.add(root);
        while (!pending.isEmpty()) {
            Hash256 id = pending.removeFirst();
            if (cluster.containsKey(id)) continue;
            MempoolEntry entry = id.equals(root) ? added : entries.get(id);
            if (entry == null) continue;
            cluster.put(id, entry);
            // Count rejection precedes size/TRUC policy. Stop before traversing
            // all clusters joined by an oversized transaction.
            if (cluster.size() > maximumCount) fail("cluster-count-limit");
            for (var input : entry.transaction().inputs()) pending.add(input.previousOutput().transactionId());
            for (int output = 0; output < entry.transaction().outputs().size(); output++) {
                Hash256 child = spent.get(new OutPoint(id, new ru.bitcoin.node.common.types.UInt32(output)));
                if (child != null) pending.add(child);
            }
        }
        return cluster;
    }


    /**
     * Core v31 cluster limits replace the legacy ancestor/descendant limits and CPFP carve-out.
     */
    static void checkLimits(Map<Hash256, MempoolEntry> entries, Hash256 added, MempoolLimits limits) {
        checkLimits(entries, added, limits, Map.of(), Set.of());
    }

    static void checkLimits(Map<Hash256, MempoolEntry> entries, Hash256 added, MempoolLimits limits,
                            Map<Hash256, MempoolEntry> previous, Set<Hash256> directConflicts) {
        checkClusterLimits(entries, added, limits);

        // TRUC version inheritance, two-generation topology and size limits remain separate policy.
        checkTrucLimits(entries, added, previous, directConflicts);
    }

    static void checkClusterLimits(Map<Hash256, MempoolEntry> entries, Hash256 added, MempoolLimits limits) {
        Set<Hash256> cluster = connected(entries, added);
        if (cluster.size() > limits.clusterCount()) fail("cluster-count-limit");
        long adjustedWeight = 0;
        for (Hash256 id : cluster) adjustedWeight = Math.addExact(adjustedWeight, entries.get(id).adjustedWeight());
        // Core's graph limit is expressed in weight units: do not round every
        // transaction separately before comparing the complete cluster.
        if (Math.ceilDiv(adjustedWeight, 4L) > limits.clusterVirtualBytes()) fail("cluster-size-limit");
    }

    static void checkTrucLimits(Map<Hash256, MempoolEntry> entries, Hash256 added,
                                        Map<Hash256, MempoolEntry> previous, Set<Hash256> directConflicts) {
        MempoolEntry entry = entries.get(added);
        Transaction tx = entry.transaction();
        Set<Hash256> parents = new HashSet<>();
        // Core checks inheritance on the new transaction's direct edges only.
        // Reorg resurrection may leave pre-existing policy-invalid edges in the
        // pool; don't reapply their admission rules to an otherwise valid child.
        for (var input : tx.inputs()) {
            var parent = entries.get(input.previousOutput().transactionId());
            if (parent == null) continue;
            if ((tx.version() == 3) != (parent.transaction().version() == 3)) throw new TrucPolicyException(tx, parent.transaction());
            parents.add(parent.transaction().txId());
        }
        if (tx.version() != 3) return;
        if (entry.virtualSize() > 10_000 || ancestors(entries, added).size() > 2) fail("truc-topology");
        if (!parents.isEmpty() && entry.virtualSize() > 1000) fail("truc-child-size");
        for (Hash256 parent : parents) {
            Set<Hash256> previousDescendants = previous.containsKey(parent)
                    ? descendants(previous, Set.of(parent)) : null;
            // Core checks the original count plus the new child. An unrelated
            // conflict can remove descendants indirectly; that must not turn
            // a forbidden family into an allowed single-transaction admission.
            boolean exceeds = previousDescendants != null ? previousDescendants.size() + 1 > 2
                    : descendants(entries, Set.of(parent)).size() > 2;
            if (!exceeds) continue;
            // SingleTRUCChecks exempts a directly conflicting descendant in the
            // pre-replacement graph, including multiple descendants after reorg.
            // A conflict elsewhere must never grant this parent's exception.
            if (previousDescendants != null && !directConflicts.isEmpty()) {
                previousDescendants.remove(parent);
                if (!Collections.disjoint(previousDescendants, directConflicts)) continue;
            }
            fail("truc-topology");
        }
    }

    /** Core's individual TRUC precheck sees parents in the original mempool,
     * not outputs temporarily supplied by other package members. */
    static void checkProbeTruc(Map<Hash256, MempoolEntry> original, Map<OutPoint, Hash256> spent,
                               MempoolEntry added) {
        checkSingleTruc(original, spent, added, Map.of(), Set.of());
    }
    static void checkSingleTruc(Map<Hash256, MempoolEntry> original, Map<OutPoint, Hash256> spent,
                                MempoolEntry added, Map<Hash256, MempoolEntry> previous, Set<Hash256> conflicts) {
        if (added.transaction().version() != 3) {
            for (var input : added.transaction().inputs()) {
                var parent = original.get(input.previousOutput().transactionId());
                if (parent != null && parent.transaction().version() == 3)
                    throw new TrucPolicyException(added.transaction(), parent.transaction());
            }
            return;
        }
        var cluster = additionCluster(original, spent, added, Integer.MAX_VALUE);
        checkTrucLimits(cluster, added.transaction().txId(), previous, conflicts);
    }

    static long trim(Map<Hash256, MempoolEntry> entries, long maximumSize, Hash256 added) {
        long removedRate = 0;
        boolean removed = false;
        while (size(entries, entries.keySet()) > maximumSize) {
            Set<Hash256> worst = null;
            long worstFee = 0, worstSize = 1, worstVsize = 1;
            for (Set<Hash256> cluster : ClusterLinearization.clusters(entries)) {
                List<ClusterLinearization.Chunk> chunks = ClusterLinearization.chunks(entries, cluster);
                ClusterLinearization.Chunk tail = chunks.getLast();
                // The final chunk is a suffix of a topological linearization:
                // all descendants are already in it. Core evicts this chunk;
                // rebuilding whole-pool child edges per cluster is redundant.
                Set<Hash256> candidate = new HashSet<>(tail.transactions());
                long fee = tail.fee();
                long bytes = tail.adjustedWeight();
                if (worst == null || ClusterLinearization.compareRate(fee, bytes, worstFee, worstSize) < 0) {
                    worst = candidate;
                    worstFee = fee;
                    worstSize = bytes;
                    // Core converts the chunk's aggregate adjusted weight to
                    // vsize once, rather than summing individually rounded sizes.
                    worstVsize = Math.ceilDiv(bytes, 4L);
                }
            }
            if (worst == null) throw new IllegalStateException("Unable to select mempool eviction candidate");
            if (added != null && worst.contains(added)) fail("mempool-full");
            // Core EvaluateFeeDown uses wide multiplication and floors signed
            // fractions. Negative mining deltas must not be clamped to zero.
            var scaled = BigInteger.valueOf(worstFee).multiply(BigInteger.valueOf(1000))
                    .divideAndRemainder(BigInteger.valueOf(worstVsize));
            long rate = scaled[0].subtract(scaled[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
            removedRate = removed ? Math.max(removedRate, rate) : rate;
            removed = true;
            worst.forEach(entries::remove);
        }
        return removedRate;
    }

    static void addSiblingConflict(Map<Hash256, MempoolEntry> entries, Map<OutPoint, Hash256> spent,
                                   Transaction transaction, Set<Hash256> conflicts) {
        if (transaction.version() != 3) return;
        Set<Hash256> parents = new HashSet<>();
        for (var input : transaction.inputs())
            if (entries.containsKey(input.previousOutput().transactionId()))
                parents.add(input.previousOutput().transactionId());
        if (parents.size() != 1) return;
        Hash256 parent = parents.iterator().next();
        if (entries.get(parent).transaction().version() != 3) return;
        // There must be exactly one direct child and no grandchildren. Use
        // the maintained spent index instead of building whole-pool adjacency.
        Hash256 sibling = null;
        var parentTx = entries.get(parent).transaction();
        for (int i = 0; i < parentTx.outputs().size(); i++) {
            Hash256 child = spent.get(new OutPoint(parent, new ru.bitcoin.node.common.types.UInt32(i)));
            if (child == null) continue;
            if (sibling != null && !sibling.equals(child)) return;
            sibling = child;
        }
        if (sibling == null) return;
        var siblingTx = entries.get(sibling).transaction();
        for (int i = 0; i < siblingTx.outputs().size(); i++)
            if (spent.containsKey(new OutPoint(sibling, new ru.bitcoin.node.common.types.UInt32(i)))) return;
        if (ancestors(entries, sibling).size() == 2) conflicts.add(sibling);
    }

    private static long size(Map<Hash256, MempoolEntry> entries, Set<Hash256> ids) {
        return ids.stream().mapToLong(id -> entries.get(id).virtualSize()).sum();
    }

    private static int compareRate(long feeA, long sizeA, long feeB, long sizeB) {
        return BigInteger.valueOf(feeA).multiply(BigInteger.valueOf(sizeB)).compareTo(
                BigInteger.valueOf(feeB).multiply(BigInteger.valueOf(sizeA)));
    }

    private static void fail(String reason) {
        throw new MempoolAdmissionException(reason);
    }
}

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
        Set<Hash256> result = new HashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var e : entries.entrySet()) {
                if (!result.contains(e.getKey()) && e.getValue().transaction().inputs().stream()
                        .anyMatch(in -> result.contains(in.previousOutput().transactionId())))
                    changed |= result.add(e.getKey());
            }
        } while (changed);
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
        int conflictingClusters = 0;
        Set<Hash256> counted = new HashSet<>();
        for (Hash256 conflict : conflicts) {
            if (counted.contains(conflict)) continue;
            Set<Hash256> cluster = ClusterLinearization.connected(entries, List.of(conflict));
            counted.addAll(cluster);
            conflictingClusters++;
            if (conflictingClusters > 100) fail("too-many-conflicting-clusters");
        }

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
        int diagram = ClusterLinearization.compareDiagrams(
                ClusterLinearization.mempoolDiagram(after),
                ClusterLinearization.mempoolDiagram(entries));
        if (diagram < 0) fail("replacement-worse-feerate-diagram");
        if (diagram == 0) fail("replacement-unchanged-feerate-diagram");
    }

    static Set<Hash256> connected(Map<Hash256, MempoolEntry> entries, Hash256 root) {
        return ClusterLinearization.connected(entries, List.of(root));
    }


    /**
     * Core v31 cluster limits replace the legacy ancestor/descendant limits and CPFP carve-out.
     */
    static void checkLimits(Map<Hash256, MempoolEntry> entries, Hash256 added, MempoolLimits limits) {
        Set<Hash256> cluster = connected(entries, added);
        if (cluster.size() > limits.clusterCount()) fail("cluster-count-limit");
        if (size(entries, cluster) > limits.clusterVirtualBytes()) fail("cluster-size-limit");

        // TRUC version inheritance, two-generation topology and size limits remain separate policy.
        Set<Hash256> ancestors = ancestors(entries, added);
        for (Hash256 id : ancestors) {
            Transaction tx = entries.get(id).transaction();
            Set<Hash256> parents = new HashSet<>();
            for (var in : tx.inputs()) {
                var parent = entries.get(in.previousOutput().transactionId());
                if (parent == null) continue;
                if ((tx.version() == 3) != (parent.transaction().version() == 3)) fail("truc-version-inheritance");
                parents.add(parent.transaction().txId());
            }
            if (tx.version() == 3) {
                if (entries.get(id).virtualSize() > 10_000 || ancestors(entries, id).size() > 2
                        || descendants(entries, Set.of(id)).size() > 2) fail("truc-topology");
                if (!parents.isEmpty() && entries.get(id).virtualSize() > 1000) fail("truc-child-size");
            }
        }
    }

    static long trim(Map<Hash256, MempoolEntry> entries, long maximumSize, Hash256 added) {
        long removedRate = 0;
        while (size(entries, entries.keySet()) > maximumSize) {
            Set<Hash256> worst = null;
            long worstFee = 0, worstSize = 1, worstVsize = 1;
            for (Set<Hash256> cluster : ClusterLinearization.clusters(entries)) {
                List<ClusterLinearization.Chunk> chunks = ClusterLinearization.chunks(entries, cluster);
                ClusterLinearization.Chunk tail = chunks.getLast();
                Set<Hash256> candidate = descendants(entries, new HashSet<>(tail.transactions()));
                long fee = candidate.stream().mapToLong(id -> entries.get(id).modifiedFee()).sum();
                long bytes = candidate.stream().mapToLong(id -> entries.get(id).adjustedWeight()).sum();
                if (worst == null || ClusterLinearization.compareRate(fee, bytes, worstFee, worstSize) < 0) {
                    worst = candidate;
                    worstFee = fee;
                    worstSize = bytes;
                    worstVsize = size(entries, candidate);
                }
            }
            if (worst == null) throw new IllegalStateException("Unable to select mempool eviction candidate");
            if (added != null && worst.contains(added)) fail("mempool-full");
            removedRate = Math.max(removedRate, Math.multiplyExact(worstFee, 1000) / worstVsize);
            worst.forEach(entries::remove);
        }
        return removedRate;
    }

    static void addSiblingConflict(Map<Hash256, MempoolEntry> entries, Transaction transaction, Set<Hash256> conflicts) {
        if (transaction.version() != 3) return;
        Set<Hash256> parents = new HashSet<>();
        for (var input : transaction.inputs())
            if (entries.containsKey(input.previousOutput().transactionId()))
                parents.add(input.previousOutput().transactionId());
        if (parents.size() != 1) return;
        Hash256 parent = parents.iterator().next();
        if (entries.get(parent).transaction().version() != 3) return;
        Set<Hash256> family = descendants(entries, Set.of(parent));
        if (family.size() != 2) return;
        family.remove(parent);
        Hash256 sibling = family.iterator().next();
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

package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import java.math.BigInteger;
import java.util.*;

/** Dependency, replacement and eviction checks on an isolated prospective pool. */
final class MempoolGraphPolicy {
    private MempoolGraphPolicy() { }
    static Set<Hash256> descendants(Map<Hash256, MempoolEntry> entries, Set<Hash256> roots) {
        Set<Hash256> result = new HashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var e : entries.entrySet()) {
                if (!result.contains(e.getKey()) && e.getValue().transaction().inputs().stream()
                        .anyMatch(in -> result.contains(in.previousOutput().transactionId()))) changed |= result.add(e.getKey());
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
        if (evicted.size() > 100) fail("too-many-replacements");
        long oldFee = 0;
        Set<OutPoint> originalInputs = new HashSet<>();
        for (Hash256 id : evicted) oldFee = Math.addExact(oldFee, entries.get(id).fee());
        for (Hash256 id : conflicts) {
            var old = entries.get(id);
            for (var in : old.transaction().inputs()) originalInputs.add(in.previousOutput());
            if (compareRate(replacement.fee(), replacement.virtualSize(), old.fee(), old.virtualSize()) <= 0) fail("replacement-feerate");
        }
        for (var in : replacement.transaction().inputs()) {
            if (entries.containsKey(in.previousOutput().transactionId()) && !originalInputs.contains(in.previousOutput())) fail("replacement-adds-unconfirmed-input");
        }
        long delta = new FeeRate(limits.incrementalRelaySatPerKvB()).feeForVSize(replacement.virtualSize());
        if (replacement.fee() < oldFee || replacement.fee() - oldFee < delta) fail("replacement-fee");
    }
    static void checkLimits(Map<Hash256, MempoolEntry> entries, Hash256 added, MempoolLimits limits) {
        Set<Hash256> ancestors = ancestors(entries, added);
        if (ancestors.size() > limits.ancestors() || size(entries, ancestors) > limits.familyVirtualBytes()) fail("ancestor-limit");
        for (Hash256 id : ancestors) {
            Set<Hash256> descendants = descendants(entries, Set.of(id));
            if (descendants.size() > limits.descendants() || size(entries, descendants) > limits.familyVirtualBytes()) fail("descendant-limit");
        }
        // TRUC version inheritance, two-generation topology and size limits.
        for (var e : entries.entrySet()) {
            Transaction tx = e.getValue().transaction();
            Set<Hash256> parents = new HashSet<>();
            for (var in : tx.inputs()) {
                var parent = entries.get(in.previousOutput().transactionId());
                if (parent == null) continue;
                if ((tx.version() == 3) != (parent.transaction().version() == 3)) fail("truc-version-inheritance");
                parents.add(parent.transaction().txId());
            }
            if (tx.version() == 3) {
                if (e.getValue().virtualSize() > 10_000 || ancestors(entries, e.getKey()).size() > 2
                        || descendants(entries, Set.of(e.getKey())).size() > 2) fail("truc-topology");
                if (!parents.isEmpty() && e.getValue().virtualSize() > 1000) fail("truc-child-size");
            }
        }
    }
    static void trim(Map<Hash256, MempoolEntry> entries, long maximumSize, Hash256 added) {
        while (size(entries, entries.keySet()) > maximumSize) {
            Set<Hash256> worst = null;
            long worstFee = 0, worstSize = 1;
            for (Hash256 id : entries.keySet()) {
                var family = descendants(entries, Set.of(id));
                long fee = family.stream().mapToLong(key -> entries.get(key).fee()).sum();
                long bytes = size(entries, family);
                if (worst == null || compareRate(fee, bytes, worstFee, worstSize) < 0) {
                    worst = family; worstFee = fee; worstSize = bytes;
                }
            }
            if (worst.contains(added)) fail("mempool-full");
            worst.forEach(entries::remove);
        }
    }
    private static long size(Map<Hash256, MempoolEntry> entries, Set<Hash256> ids) {
        return ids.stream().mapToLong(id -> entries.get(id).virtualSize()).sum();
    }
    private static int compareRate(long feeA, long sizeA, long feeB, long sizeB) {
        return BigInteger.valueOf(feeA).multiply(BigInteger.valueOf(sizeB)).compareTo(
                BigInteger.valueOf(feeB).multiply(BigInteger.valueOf(sizeA)));
    }
    private static void fail(String reason) { throw new MempoolAdmissionException(reason); }
}

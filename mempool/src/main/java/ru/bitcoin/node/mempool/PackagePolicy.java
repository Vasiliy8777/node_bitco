package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

final class PackagePolicy {
    private PackagePolicy() {
    }

    static void validate(List<Transaction> txs) {
        if (txs.isEmpty() || txs.size() > 25) fail("package-count");
        Set<Hash256> later = new HashSet<>();
        long weight = 0;
        for (var tx : txs) {
            Objects.requireNonNull(tx);
            if (!later.add(tx.txId())) fail("package-duplicates");
            weight += TransactionWeight.calculate(tx);
        }
        if (txs.size() > 1 && weight > 404_000) fail("package-weight");
        Set<OutPoint> spends = new HashSet<>();
        for (var tx : txs) {
            for (var in : tx.inputs()) {
                if (later.contains(in.previousOutput().transactionId())) fail("package-not-topological");
                if (!spends.add(in.previousOutput())) fail("package-conflict");
            }
            later.remove(tx.txId());
        }
        if (txs.size() < 2) return;
        Set<Hash256> parents = new HashSet<>();
        for (int i = 0; i < txs.size() - 1; i++) parents.add(txs.get(i).txId());
        Set<Hash256> childParents = new HashSet<>();
        for (var in : txs.getLast().inputs()) childParents.add(in.previousOutput().transactionId());
        if (!childParents.containsAll(parents)) fail("package-not-child-with-parents");
        for (int i = 0; i < txs.size() - 1; i++)
            for (var in : txs.get(i).inputs()) {
                if (parents.contains(in.previousOutput().transactionId())) fail("package-parents-not-independent");
            }
    }

    static void ephemeralSpends(Transaction tx, Map<Hash256, MempoolEntry> entries, long dustRate) {
        Set<OutPoint> required = new HashSet<>();
        for (var in : tx.inputs()) {
            var parent = entries.get(in.previousOutput().transactionId());
            if (parent == null) continue;
            for (int i = 0; i < parent.transaction().outputs().size(); i++) {
                var output = parent.transaction().outputs().get(i);
                if (output.value() < ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.dustThreshold(output, dustRate)) {
                    required.add(new OutPoint(parent.transaction().txId(), new ru.bitcoin.node.common.types.UInt32(i)));
                }
            }
        }
        for (var in : tx.inputs()) required.remove(in.previousOutput());
        if (!required.isEmpty()) fail("missing-ephemeral-spends");
    }

    static void replacement(List<Transaction> txs, Map<Hash256, MempoolEntry> before,
                            Map<Hash256, MempoolEntry> after, Set<Hash256> conflicts,
                            Set<Hash256> removed, MempoolLimits limits) {
        if (conflicts.isEmpty()) return;
        if (txs.size() != 2) fail("package-rbf-requires-one-parent-one-child");
        Set<Hash256> proposed = Set.of(txs.getFirst().txId(), txs.getLast().txId());
        long potential = 0;
        for (Hash256 id : conflicts) potential += MempoolGraphPolicy.descendants(before, Set.of(id)).size();
        if (potential > 100) fail("package-rbf-too-many-conflicts");
        for (var tx : txs)
            for (var input : tx.inputs()) {
                Hash256 id = input.previousOutput().transactionId();
                if (before.containsKey(id) && !proposed.contains(id)) fail("package-rbf-mempool-ancestor");
            }
        long oldFee = removed.stream().mapToLong(id -> before.get(id).fee()).sum();
        long newFee = proposed.stream().mapToLong(id -> after.get(id).fee()).sum();
        long newSize = proposed.stream().mapToLong(id -> after.get(id).virtualSize()).sum();
        if (newFee < oldFee || newFee - oldFee < new FeeRate(limits.incrementalRelaySatPerKvB()).feeForVSize(newSize))
            fail("package-rbf-fees");
        var parent = after.get(txs.getFirst().txId());
        if (newFee * 1000 / newSize <= parent.fee() * 1000 / parent.virtualSize())
            fail("package-rbf-child-must-improve-parent-rate");
        Set<Hash256> oldAffected = ClusterLinearization.connected(before, conflicts);
        List<ClusterLinearization.Chunk> oldCurve = ClusterLinearization.chunks(before, oldAffected);
        Set<Hash256> newRoots = new HashSet<>(oldAffected);
        newRoots.removeAll(removed);
        newRoots.addAll(proposed);
        Set<Hash256> newAffected = ClusterLinearization.connected(after, newRoots);
        List<ClusterLinearization.Chunk> newCurve = ClusterLinearization.chunks(after, newAffected);
        int diagram = ClusterLinearization.compareDiagrams(newCurve, oldCurve);
        if (diagram < 0) fail("package-rbf-worse-feerate-diagram");
        if (diagram == 0) fail("package-rbf-unchanged-feerate-diagram");
    }

    private static void fail(String message) {
        throw new MempoolAdmissionException(message);
    }
}

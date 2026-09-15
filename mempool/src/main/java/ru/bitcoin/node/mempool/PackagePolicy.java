package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.*;
import java.math.BigInteger;
import java.util.*;

final class PackagePolicy {
    private PackagePolicy() { }
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
        for (int i = 0; i < txs.size()-1; i++) parents.add(txs.get(i).txId());
        Set<Hash256> childParents = new HashSet<>();
        for (var in : txs.getLast().inputs()) childParents.add(in.previousOutput().transactionId());
        if (!childParents.containsAll(parents)) fail("package-not-child-with-parents");
        for (int i = 0; i < txs.size()-1; i++) for (var in : txs.get(i).inputs()) {
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
        for (var tx : txs) for (var input : tx.inputs()) {
            Hash256 id = input.previousOutput().transactionId();
            if (before.containsKey(id) && !proposed.contains(id)) fail("package-rbf-mempool-ancestor");
        }
        long oldFee = removed.stream().mapToLong(id -> before.get(id).fee()).sum();
        long newFee = proposed.stream().mapToLong(id -> after.get(id).fee()).sum();
        long newSize = proposed.stream().mapToLong(id -> after.get(id).virtualSize()).sum();
        if (newFee < oldFee || newFee-oldFee < new FeeRate(limits.incrementalRelaySatPerKvB()).feeForVSize(newSize)) fail("package-rbf-fees");
        var parent = after.get(txs.getFirst().txId());
        if (newFee * 1000 / newSize <= parent.fee() * 1000 / parent.virtualSize()) fail("package-rbf-child-must-improve-parent-rate");
        Set<Hash256> affected = connected(before, conflicts);
        List<Chunk> oldCurve = chunks(before, affected);
        affected.removeAll(removed);
        affected.addAll(proposed);
        List<Chunk> newCurve = chunks(after, affected);
        TreeSet<Long> points = new TreeSet<>();
        for (var curve : List.of(oldCurve, newCurve)) {
            long x=0; for (var chunk : curve) { x+=chunk.size; points.add(x); }
        }
        boolean improved = false;
        for (long x : points) {
            var oldValue = valueAt(oldCurve,x); var newValue = valueAt(newCurve,x);
            int comparison = newValue[0].multiply(oldValue[1]).compareTo(oldValue[0].multiply(newValue[1]));
            if (comparison < 0) fail("package-rbf-worse-feerate-diagram");
            improved |= comparison > 0;
        }
        if (!improved) fail("package-rbf-unchanged-feerate-diagram");
    }
    private record Chunk(long fee,long size) { }
    private static List<Chunk> chunks(Map<Hash256,MempoolEntry> entries, Set<Hash256> ids) {
        Set<Hash256> remaining = new HashSet<>(ids);
        List<Chunk> chunks = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Hash256 root=remaining.iterator().next();
            Set<Hash256> component=connected(entries,Set.of(root));
            if (component.size()>2) fail("package-rbf-cluster-too-large");
            remaining.removeAll(component);
            var members=component.stream().map(entries::get).toList();
            if(members.size()==1) chunks.add(new Chunk(members.getFirst().fee(),members.getFirst().virtualSize()));
            else {
                var a=members.get(0); var b=members.get(1);
                boolean aIsParent=b.transaction().inputs().stream().anyMatch(in -> in.previousOutput().transactionId().equals(a.transaction().txId()));
                var parent=aIsParent?a:b; var child=aIsParent?b:a;
                if (rateCompare(parent.fee(),parent.virtualSize(),child.fee(),child.virtualSize())<0)
                    chunks.add(new Chunk(a.fee()+b.fee(),a.virtualSize()+b.virtualSize()));
                else { chunks.add(new Chunk(a.fee(),a.virtualSize())); chunks.add(new Chunk(b.fee(),b.virtualSize())); }
            }
        }
        chunks.sort((a,b) -> -rateCompare(a.fee,a.size,b.fee,b.size));
        return chunks;
    }
    private static Set<Hash256> connected(Map<Hash256,MempoolEntry> entries,Set<Hash256> roots) {
        Set<Hash256> result=new HashSet<>(roots);
        boolean changed;
        do {
            int size=result.size();
            result.addAll(MempoolGraphPolicy.descendants(entries,result));
            for(Hash256 id:List.copyOf(result)) result.addAll(MempoolGraphPolicy.ancestors(entries,id));
            changed=size!=result.size();
        } while(changed);
        return result;
    }
    private static BigInteger[] valueAt(List<Chunk> curve,long x) {
        long fee=0;
        for(var chunk:curve) {
            if(x<chunk.size) return new BigInteger[]{BigInteger.valueOf(fee).multiply(BigInteger.valueOf(chunk.size)).add(BigInteger.valueOf(chunk.fee).multiply(BigInteger.valueOf(x))),BigInteger.valueOf(chunk.size)};
            fee+=chunk.fee; x-=chunk.size;
        }
        return new BigInteger[]{BigInteger.valueOf(fee),BigInteger.ONE};
    }
    private static int rateCompare(long fa,long sa,long fb,long sb) {
        return BigInteger.valueOf(fa).multiply(BigInteger.valueOf(sb)).compareTo(BigInteger.valueOf(fb).multiply(BigInteger.valueOf(sa)));
    }
    private static void fail(String message) { throw new MempoolAdmissionException(message); }
}

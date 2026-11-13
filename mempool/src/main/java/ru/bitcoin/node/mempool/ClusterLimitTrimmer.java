/* Copyright (c) The Bitcoin Core developers and bitcoin-node contributors.
 * Distributed under the MIT software license. Algorithm: Bitcoin Core v32.1
 * src/txgraph.cpp (TxGraphImpl::Trim).
 */
package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import java.util.*;

/** Core TxGraph::Trim: preserve existing orders while admitting deferred dependencies. */
final class ClusterLimitTrimmer {
    private ClusterLimitTrimmer() { }
    record Dependency(Hash256 parent, Hash256 child) { }
    private static final class Node {
        final Hash256 id;
        final long weight, chunkFee, chunkWeight;
        final Set<Node> parents = new LinkedHashSet<>(), children = new LinkedHashSet<>();
        Node root = this;
        int remaining, count = 1;
        long totalWeight;
        boolean included;
        Node(Hash256 id, long weight, long chunkFee, long chunkWeight) {
            this.id=id; this.weight=weight; this.totalWeight=weight;
            this.chunkFee=chunkFee; this.chunkWeight=chunkWeight;
        }
        Node representative() {
            Node current=this;
            while (current.root!=current) { current.root=current.root.root; current=current.root; }
            return current;
        }
    }
    static Set<Hash256> removed(Map<Hash256,MempoolEntry> entries,
                               Collection<? extends List<ClusterLinearization.Chunk>> existing,
                               Collection<Dependency> dependencies, int countLimit, long weightLimit) {
        if (countLimit<1 || weightLimit<1) throw new IllegalArgumentException("Invalid cluster limits");
        var nodes = new LinkedHashMap<Hash256,Node>();
        var implicit = new ArrayList<Dependency>();
        for (var cluster : existing) {
            Hash256 previous=null;
            for (var chunk : cluster) for (var id : chunk.transactions()) {
                var entry=Objects.requireNonNull(entries.get(id),"Unknown trim member");
                if (nodes.put(id,new Node(id,entry.adjustedWeight(),chunk.fee(),chunk.adjustedWeight()))!=null)
                    throw new IllegalArgumentException("Repeated trim member");
                if (previous!=null) implicit.add(new Dependency(previous,id));
                previous=id;
            }
        }
        if (!nodes.keySet().equals(entries.keySet())) throw new IllegalArgumentException("Incomplete trim orders");
        var links = new ArrayList<>(dependencies); links.addAll(implicit);
        for (var link : links) {
            var parent=Objects.requireNonNull(nodes.get(link.parent()));
            var child=Objects.requireNonNull(nodes.get(link.child()));
            if (parent==child) throw new IllegalArgumentException("Self dependency");
            if (child.parents.add(parent)) parent.children.add(child);
        }
        var ready = new PriorityQueue<Node>((a,b) -> {
            int rate=ClusterLinearization.compareRate(b.chunkFee,b.chunkWeight,a.chunkFee,a.chunkWeight);
            return rate!=0 ? rate : Long.compare(a.chunkWeight,b.chunkWeight);
        });
        for (var node : nodes.values()) {
            node.remaining=node.parents.size();
            if (node.remaining==0 && node.weight<=weightLimit) ready.add(node);
        }
        while (!ready.isEmpty()) {
            var node=ready.remove();
            var roots=new HashSet<Node>();
            for (var parent : node.parents) roots.add(parent.representative());
            long weight=node.weight; int count=1;
            for (var root : roots) {weight=Math.addExact(weight,root.totalWeight); count=Math.addExact(count,root.count);}
            if (count>countLimit || weight>weightLimit) continue;
            var root=node;
            for (var other : roots) {
                if (root.count<other.count) { var swap=root; root=other; other=swap; }
                other.root=root; root.count+=other.count; root.totalWeight+=other.totalWeight;
            }
            node.included=true;
            for (var child : node.children) if (--child.remaining==0) ready.add(child);
        }
        var removed=new LinkedHashSet<Hash256>();
        for (var node : nodes.values()) if (!node.included) removed.add(node.id);
        return Set.copyOf(removed);
    }
}

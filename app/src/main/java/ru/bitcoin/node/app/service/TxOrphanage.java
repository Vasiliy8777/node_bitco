package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.mempool.MempoolPolicy;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.*;

/**
 * Bounded transaction orphanage keyed by wtxid with reverse parent indexing.
 *
 * <p>Like Bitcoin Core's TxOrphanage, missing-input transactions are treated as
 * untrusted relay data: individual transactions are limited to standard weight,
 * storage is resource bounded, duplicate witness transactions are deduplicated,
 * announcers are tracked independently, and children can be reconsidered without
 * rescanning the complete orphan set.</p>
 *
 * <p>Not thread-safe. NodeRelayService owns it from its single relay worker,
 * except peer-close cleanup which is synchronized by the public methods.</p>
 */
final class TxOrphanage {
    static final long RESERVED_WEIGHT_PER_PEER = 404_000L;
    static final int MAX_GLOBAL_LATENCY_SCORE = 3_000;

    private static final class Entry {
        final Transaction transaction;
        final Hash256 wtxid;
        final long weight;
        final int latencyScore;
        final long sequence;
        final Set<Peer> announcers = Collections.newSetFromMap(new IdentityHashMap<>());

        Entry(Transaction transaction, Peer peer, long sequence) {
            this.transaction = transaction;
            this.wtxid = transaction.wtxId();
            this.weight = TransactionWeight.calculate(transaction);
            this.latencyScore = 1 + transaction.inputs().size() / 10;
            this.sequence = sequence;
            this.announcers.add(peer);
        }
    }

    private final long reservedWeightPerPeer;
    private final int maxGlobalLatencyScore;
    private final LinkedHashMap<Hash256, Entry> byWtxid = new LinkedHashMap<>();
    private final Map<Hash256, LinkedHashSet<Hash256>> childrenByParentTxid = new HashMap<>();
    private final Map<OutPoint, LinkedHashSet<Hash256>> spendersByPrevout = new HashMap<>();
    private long totalWeight;
    private int totalLatencyScore;
    private long sequence;

    TxOrphanage() {
        this(RESERVED_WEIGHT_PER_PEER, MAX_GLOBAL_LATENCY_SCORE);
    }

    TxOrphanage(long reservedWeightPerPeer, int maxGlobalLatencyScore) {
        if (reservedWeightPerPeer <= 0 || maxGlobalLatencyScore <= 0)
            throw new IllegalArgumentException("orphanage limits must be positive");
        this.reservedWeightPerPeer = reservedWeightPerPeer;
        this.maxGlobalLatencyScore = maxGlobalLatencyScore;
    }

    synchronized boolean add(Transaction transaction, Peer peer) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(peer, "peer");
        long weight = TransactionWeight.calculate(transaction);
        if (weight > MempoolPolicy.MAX_STANDARD_TX_WEIGHT) return false;

        Hash256 wtxid = transaction.wtxId();
        Entry existing = byWtxid.get(wtxid);
        if (existing != null) {
            existing.announcers.add(peer);
            return false;
        }

        Entry entry = new Entry(transaction, peer, sequence++);
        byWtxid.put(wtxid, entry);
        totalWeight = Math.addExact(totalWeight, entry.weight);
        totalLatencyScore = Math.addExact(totalLatencyScore, entry.latencyScore);
        for (var input : transaction.inputs()) {
            childrenByParentTxid.computeIfAbsent(input.previousOutput().transactionId(), ignored -> new LinkedHashSet<>())
                    .add(wtxid);
            spendersByPrevout.computeIfAbsent(input.previousOutput(), ignored -> new LinkedHashSet<>()).add(wtxid);
        }
        trim();
        return byWtxid.containsKey(wtxid);
    }

    synchronized List<Transaction> childrenOf(Hash256 parentTxid) {
        LinkedHashSet<Hash256> ids = childrenByParentTxid.get(parentTxid);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Transaction> result = new ArrayList<>(ids.size());
        for (Hash256 id : List.copyOf(ids)) {
            Entry entry = byWtxid.get(id);
            if (entry != null) result.add(entry.transaction);
        }
        return List.copyOf(result);
    }

    synchronized boolean remove(Transaction transaction) {
        return removeByWtxid(transaction.wtxId());
    }

    synchronized void removeConflicts(Transaction transaction) {
        LinkedHashSet<Hash256> conflicts = new LinkedHashSet<>();
        for (var input : transaction.inputs()) {
            Set<Hash256> ids = spendersByPrevout.get(input.previousOutput());
            if (ids != null) conflicts.addAll(ids);
        }
        for (Hash256 wtxid : conflicts) removeByWtxid(wtxid);
    }

    synchronized void removePeer(Peer peer) {
        for (Entry entry : List.copyOf(byWtxid.values())) {
            entry.announcers.remove(peer);
            if (entry.announcers.isEmpty()) removeByWtxid(entry.wtxid);
        }
        trim();
    }

    synchronized int size() { return byWtxid.size(); }
    synchronized long totalWeight() { return totalWeight; }
    synchronized int totalLatencyScore() { return totalLatencyScore; }

    private void trim() {
        while (!byWtxid.isEmpty() && (totalLatencyScore > maxGlobalLatencyScore
                || totalWeight > globalWeightLimit())) {
            Peer worstPeer = mostResourceIntensivePeer();
            Entry victim = oldestFromPeer(worstPeer);
            if (victim == null) victim = byWtxid.values().iterator().next();
            // Drop only this peer's announcement when another peer also announced it.
            victim.announcers.remove(worstPeer);
            if (victim.announcers.isEmpty()) removeByWtxid(victim.wtxid);
        }
    }

    private long globalWeightLimit() {
        Set<Peer> peers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entry entry : byWtxid.values()) peers.addAll(entry.announcers);
        return Math.max(reservedWeightPerPeer,
                Math.multiplyExact(reservedWeightPerPeer, Math.max(1, peers.size())));
    }

    private Peer mostResourceIntensivePeer() {
        IdentityHashMap<Peer, Long> weight = new IdentityHashMap<>();
        IdentityHashMap<Peer, Integer> latency = new IdentityHashMap<>();
        for (Entry entry : byWtxid.values()) {
            for (Peer peer : entry.announcers) {
                weight.merge(peer, entry.weight, Long::sum);
                latency.merge(peer, entry.latencyScore, Integer::sum);
            }
        }
        Peer worst = null;
        double worstScore = -1;
        for (Peer peer : weight.keySet()) {
            double score = Math.max((double) weight.get(peer) / reservedWeightPerPeer,
                    (double) latency.getOrDefault(peer, 0) / Math.max(1, maxGlobalLatencyScore));
            if (score > worstScore) { worstScore = score; worst = peer; }
        }
        return worst;
    }

    private Entry oldestFromPeer(Peer peer) {
        if (peer == null) return null;
        Entry oldest = null;
        for (Entry entry : byWtxid.values()) {
            if (entry.announcers.contains(peer) && (oldest == null || entry.sequence < oldest.sequence)) oldest = entry;
        }
        return oldest;
    }

    private boolean removeByWtxid(Hash256 wtxid) {
        Entry entry = byWtxid.remove(wtxid);
        if (entry == null) return false;
        totalWeight -= entry.weight;
        totalLatencyScore -= entry.latencyScore;
        for (var input : entry.transaction.inputs()) {
            Hash256 parent = input.previousOutput().transactionId();
            LinkedHashSet<Hash256> ids = childrenByParentTxid.get(parent);
            if (ids != null) {
                ids.remove(wtxid);
                if (ids.isEmpty()) childrenByParentTxid.remove(parent);
            }
            LinkedHashSet<Hash256> spenders = spendersByPrevout.get(input.previousOutput());
            if (spenders != null) {
                spenders.remove(wtxid);
                if (spenders.isEmpty()) spendersByPrevout.remove(input.previousOutput());
            }
        }
        return true;
    }
}

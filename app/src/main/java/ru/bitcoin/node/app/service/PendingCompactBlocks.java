package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.message.CompactBlockReconstruction;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.*;

/** Bounded retained reconstructions, shared by the relay worker and peer close callbacks. */
final class PendingCompactBlocks {
    enum Admission { STORED, DUPLICATE, REJECTED }
    record Pending(CompactBlockReconstruction.Partial partial, long expires, long bytes) { }
    record Expired(Peer peer, Hash256 hash) { }
    private final long peerLimit;
    private final long globalLimit;
    private final int countLimit;
    private final Map<Peer, Map<Hash256, Pending>> entries = new HashMap<>();
    private final Map<Peer, Long> peerBytes = new HashMap<>();
    private long retained;

    PendingCompactBlocks() { this(16_000_000, 64_000_000, 16); }
    PendingCompactBlocks(long peerLimit, long globalLimit, int countLimit) {
        if (peerLimit <= 0 || globalLimit <= 0 || countLimit <= 0) throw new IllegalArgumentException("limits");
        this.peerLimit = peerLimit;
        this.globalLimit = globalLimit;
        this.countLimit = countLimit;
    }
    synchronized void register(Peer peer) { entries.computeIfAbsent(peer, ignored -> new HashMap<>()); }
    synchronized boolean contains(Peer peer, Hash256 hash) {
        var byHash = entries.get(peer);
        return byHash != null && byHash.containsKey(hash);
    }
    synchronized Admission add(Peer peer, Hash256 hash, CompactBlockReconstruction.Partial partial,
                               long expires, long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("bytes");
        var byHash = entries.get(peer);
        if (byHash == null) return Admission.REJECTED;
        if (byHash.containsKey(hash)) return Admission.DUPLICATE;
        long used = peerBytes.getOrDefault(peer, 0L);
        if (byHash.size() >= countLimit || bytes > peerLimit - used || bytes > globalLimit - retained) {
            return Admission.REJECTED;
        }
        byHash.put(hash, new Pending(partial, expires, bytes));
        peerBytes.put(peer, used + bytes);
        retained += bytes;
        return Admission.STORED;
    }
    synchronized Pending take(Peer peer, Hash256 hash) {
        var byHash = entries.get(peer);
        Pending pending = byHash == null ? null : byHash.remove(hash);
        if (pending != null) release(peer, pending.bytes());
        return pending;
    }
    synchronized List<Expired> expire(long now) {
        var expired = new ArrayList<Expired>();
        for (var peerEntry : entries.entrySet()) {
            var iterator = peerEntry.getValue().entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (now - entry.getValue().expires() >= 0) {
                    release(peerEntry.getKey(), entry.getValue().bytes());
                    expired.add(new Expired(peerEntry.getKey(), entry.getKey()));
                    iterator.remove();
                }
            }
        }
        return List.copyOf(expired);
    }
    synchronized void removePeer(Peer peer) {
        entries.remove(peer);
        retained -= peerBytes.getOrDefault(peer, 0L);
        peerBytes.remove(peer);
    }
    synchronized void clear() { entries.clear(); peerBytes.clear(); retained = 0; }
    synchronized long retainedBytes() { return retained; }
    private void release(Peer peer, long bytes) {
        retained -= bytes;
        long remaining = peerBytes.getOrDefault(peer, 0L) - bytes;
        if (remaining == 0) peerBytes.remove(peer); else peerBytes.put(peer, remaining);
    }

    /** Conservative accounting units, including pinned mempool transactions, not a JVM heap measurement. */
    static long estimateBytes(CompactBlockReconstruction.Partial partial) {
        long bytes = 1024L + 64L * partial.compactBlock().transactionCount();
        Set<Transaction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Transaction tx : partial.transactions()) {
            if (tx != null && seen.add(tx)) {
                bytes += 1024L + 32L * TransactionSerializer.serialize(tx).length;
            }
        }
        return bytes;
    }
}

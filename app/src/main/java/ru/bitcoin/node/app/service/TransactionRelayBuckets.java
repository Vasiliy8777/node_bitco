package ru.bitcoin.node.app.service;

import ru.bitcoin.node.common.types.Hash256;

import java.util.*;

/**
 * Core 32.1 InvToSendBucket: one rate budget per direction, independent of peer count.
 * Adapted from net_processing.cpp, copyright the Bitcoin Core developers.
 * MIT license is included in the mempool dependency's META-INF/licenses/bitcoin-core-MIT.txt.
 */
final class TransactionRelayBuckets {
    record Transaction(Hash256 txid, Hash256 wtxid, long feeRate, int bytes) {
        Transaction {
            Objects.requireNonNull(txid); Objects.requireNonNull(wtxid);
            if (bytes <= 0 || feeRate < 0) throw new IllegalArgumentException("transaction size/fee");
        }
    }
    record Batch(List<Transaction> inbound, List<Transaction> outbound) { }
    private final Bucket inbound;
    private final Bucket outbound;

    TransactionRelayBuckets(int rate) {
        if (rate < 1) throw new IllegalArgumentException("txsendrate must be positive");
        inbound = new Bucket(rate, 1);
        outbound = new Bucket(rate, 2.5);
    }

    synchronized void add(Hash256 wtxid) {
        inbound.pending.add(wtxid);
        outbound.pending.add(wtxid);
    }

    synchronized boolean ready(long now) {
        inbound.refill(now); outbound.refill(now);
        return inbound.ready() || outbound.ready();
    }

    /** miningOrder must be an atomic, topological, descending mining-score mempool snapshot. */
    synchronized Batch drain(long now, List<Transaction> miningOrder, boolean hasInbound, boolean hasOutbound) {
        inbound.refill(now); outbound.refill(now);
        var live = new HashSet<Hash256>();
        for (var tx : miningOrder) live.add(tx.wtxid());
        inbound.pending.retainAll(live); outbound.pending.retainAll(live);
        var in = hasInbound ? inbound.take(miningOrder) : List.<Transaction>of();
        var out = hasOutbound ? outbound.take(miningOrder) : List.<Transaction>of();
        if (!hasInbound) inbound.pending.clear();
        if (!hasOutbound) outbound.pending.clear();
        return new Batch(in, out);
    }

    synchronized Map<String, Object> info() {
        return Map.of("inbound", inbound.info(), "outbound", outbound.info());
    }

    synchronized void clear() { inbound.pending.clear(); outbound.pending.clear(); }

    private static final class Bucket {
        final Set<Hash256> pending = new LinkedHashSet<>();
        final double rate, multiplier, countCap, countFloor;
        double count, size = 12_000_000;
        long last;
        boolean initialized;
        Bucket(int rate, double multiplier) {
            this.rate = rate; this.multiplier = multiplier;
            count = countCap = rate * 30.0;
            countFloor = -rate * 5.0;
        }
        void refill(long now) {
            if (initialized && now > last) {
                double elapsed = (now - last) / 1_000_000_000.0;
                count = Math.min(countCap, count + elapsed * rate * multiplier);
                size = Math.min(50_000_000, size + elapsed * 20_000 * multiplier);
            }
            last = now; initialized = true;
        }
        boolean ready() { return !pending.isEmpty() && count > 0 && size > 0; }
        List<Transaction> take(List<Transaction> order) {
            if (!ready()) return List.of();
            int maximum = (int) Math.min(Integer.MAX_VALUE, Math.max(count - countFloor, 0));
            var result = new ArrayList<Transaction>();
            for (var tx : order) {
                if (!pending.remove(tx.wtxid())) continue;
                result.add(tx); count--; size -= tx.bytes();
                if (result.size() >= maximum || count <= countFloor || size <= -50_000) break;
            }
            return List.copyOf(result);
        }
        Map<String, Object> info() {
            return Map.of("backlog", pending.size(), "count_tok", count, "size_tok", size);
        }
    }
}

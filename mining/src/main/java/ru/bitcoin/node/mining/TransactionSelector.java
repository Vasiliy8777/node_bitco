package ru.bitcoin.node.mining;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.*;

/** Selects transactions by dependency-valid cluster feerate chunks. */
public final class TransactionSelector {
    private TransactionSelector() { }

    public static List<Transaction> select(List<MempoolEntry> snapshot, long weightBudget,
                                           long sigopsBudget, FeeRate minimumRate) {
        if (weightBudget < 0 || sigopsBudget < 0) throw new IllegalArgumentException("Negative budget");
        Objects.requireNonNull(minimumRate, "minimumRate");
        Map<Hash256,MempoolEntry> pool = new LinkedHashMap<>();
        for (MempoolEntry entry : List.copyOf(snapshot)) {
            if (entry.fee() < 0 || entry.weight() <= 0 || entry.sigOpCost() < 0 || entry.transaction().isCoinbase())
                throw new IllegalArgumentException("Invalid mempool entry");
            if (pool.put(entry.transaction().txId(), entry) != null) throw new IllegalArgumentException("Duplicate txid");
        }

        PriorityQueue<Cursor> cursors = new PriorityQueue<>((a, b) -> {
            var left = a.peek();
            var right = b.peek();
            int rate = ClusterLinearization.compareRate(right.fee(), right.adjustedWeight(),
                    left.fee(), left.adjustedWeight());
            return rate != 0 ? rate : Integer.compare(a.ordinal, b.ordinal);
        });
        int ordinal = 0;
        for (Set<Hash256> cluster : ClusterLinearization.clusters(pool))
            cursors.add(new Cursor(ClusterLinearization.chunks(pool, cluster), ordinal++));

        List<Transaction> result = new ArrayList<>();
        while (!cursors.isEmpty()) {
            Cursor best = cursors.remove();
            ClusterLinearization.Chunk chunk = best.peek();
            if (chunk.fee() < minimumRate.feeForVSize(chunk.virtualSize())) continue;
            long weight = 0, sigops = 0;
            for (Hash256 id : chunk.transactions()) {
                MempoolEntry e = pool.get(id);
                weight = Math.addExact(weight, e.weight()); sigops = Math.addExact(sigops, e.sigOpCost());
            }
            if (weight > weightBudget || sigops > sigopsBudget) continue;
            ClusterLinearization.Chunk chosen = best.take();
            for (Hash256 id : chosen.transactions()) {
                MempoolEntry e = pool.get(id); result.add(e.transaction());
                weightBudget -= e.weight(); sigopsBudget -= e.sigOpCost();
            }
            if (best.peek() != null) cursors.add(best);
        }
        return List.copyOf(result);
    }

    private static final class Cursor {
        private final List<ClusterLinearization.Chunk> chunks;
        private final int ordinal;
        private int index;
        private Cursor(List<ClusterLinearization.Chunk> chunks, int ordinal) { this.chunks = chunks; this.ordinal = ordinal; }
        private ClusterLinearization.Chunk peek() { return index >= chunks.size() ? null : chunks.get(index); }
        private ClusterLinearization.Chunk take() { return chunks.get(index++); }
    }
}

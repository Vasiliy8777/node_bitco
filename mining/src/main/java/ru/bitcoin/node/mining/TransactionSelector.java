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

        List<Cursor> cursors = new ArrayList<>();
        for (Set<Hash256> cluster : ClusterLinearization.clusters(pool))
            cursors.add(new Cursor(ClusterLinearization.chunks(pool, cluster)));

        List<Transaction> result = new ArrayList<>();
        while (true) {
            Cursor best = null;
            for (Cursor cursor : cursors) {
                ClusterLinearization.Chunk chunk = cursor.peek();
                if (chunk == null) continue;
                if (chunk.fee() < minimumRate.feeForVSize(chunk.virtualSize())) { cursor.blocked = true; continue; }
                long weight = 0, sigops = 0;
                for (Hash256 id : chunk.transactions()) {
                    MempoolEntry e = pool.get(id);
                    weight = Math.addExact(weight, e.weight()); sigops = Math.addExact(sigops, e.sigOpCost());
                }
                if (weight > weightBudget || sigops > sigopsBudget) { cursor.blocked = true; continue; }
                if (best == null || ClusterLinearization.compareRate(chunk.fee(), chunk.adjustedWeight(),
                        best.peek().fee(), best.peek().adjustedWeight()) > 0) best = cursor;
            }
            if (best == null) return List.copyOf(result);
            ClusterLinearization.Chunk chosen = best.take();
            for (Hash256 id : chosen.transactions()) {
                MempoolEntry e = pool.get(id); result.add(e.transaction());
                weightBudget -= e.weight(); sigopsBudget -= e.sigOpCost();
            }
        }
    }

    private static final class Cursor {
        private final List<ClusterLinearization.Chunk> chunks;
        private int index;
        private boolean blocked;
        private Cursor(List<ClusterLinearization.Chunk> chunks) { this.chunks = chunks; }
        private ClusterLinearization.Chunk peek() { return blocked || index >= chunks.size() ? null : chunks.get(index); }
        private ClusterLinearization.Chunk take() { return chunks.get(index++); }
    }
}

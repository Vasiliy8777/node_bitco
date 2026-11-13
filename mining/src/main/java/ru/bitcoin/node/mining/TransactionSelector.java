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
        return select(snapshot, null, weightBudget, sigopsBudget, minimumRate);
    }

    public static List<Transaction> selectSnapshot(Mempool.MiningSnapshot snapshot, long weightBudget,
                                                   long sigopsBudget, FeeRate minimumRate) {
        return select(snapshot.entries(), snapshot.clusters(), weightBudget, sigopsBudget, minimumRate);
    }

    private static List<Transaction> select(List<MempoolEntry> snapshot,
                                            List<List<ClusterLinearization.Chunk>> orderedClusters,
                                            long weightBudget, long sigopsBudget, FeeRate minimumRate) {
        if (weightBudget < 0 || sigopsBudget < 0) throw new IllegalArgumentException("Negative budget");
        Objects.requireNonNull(minimumRate, "minimumRate");
        Map<Hash256,MempoolEntry> pool = new LinkedHashMap<>();
        for (MempoolEntry entry : List.copyOf(snapshot)) {
            if (entry.fee() < 0 || entry.weight() <= 0 || entry.sigOpCost() < 0 || entry.transaction().isCoinbase())
                throw new IllegalArgumentException("Invalid mempool entry");
            if (pool.put(entry.transaction().txId(), entry) != null) throw new IllegalArgumentException("Duplicate txid");
        }

        PriorityQueue<Cursor> cursors = new PriorityQueue<>(Comparator.comparing(cursor -> cursor.key));
        if (orderedClusters == null)
            orderedClusters = ClusterLinearization.clusters(pool).stream().map(cluster -> ClusterLinearization.chunks(pool, cluster)).toList();
        for (var cluster : orderedClusters) if (!cluster.isEmpty()) cursors.add(new Cursor(cluster));

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
        private int index;
        private ClusterLinearization.ChunkOrderKey key;
        private Cursor(List<ClusterLinearization.Chunk> chunks) {
            this.chunks = chunks;
            key = ClusterLinearization.chunkOrderKey(chunks.getFirst(), chunks.getFirst().adjustedWeight());
        }
        private ClusterLinearization.Chunk peek() { return index >= chunks.size() ? null : chunks.get(index); }
        private ClusterLinearization.Chunk take() {
            var chosen = chunks.get(index++);
            var next = peek();
            if (next != null) {
                long prefix = ClusterLinearization.compareRate(chosen.fee(), chosen.adjustedWeight(), next.fee(), next.adjustedWeight()) == 0
                        ? Math.addExact(key.equalRatePrefixWeight(), next.adjustedWeight()) : next.adjustedWeight();
                key = ClusterLinearization.chunkOrderKey(next, prefix);
            }
            return chosen;
        }
    }
}

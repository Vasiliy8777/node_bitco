package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import java.util.*;
import java.util.function.BiFunction;

/** Owned by the mempool monitor. Retains bounded metadata, never transactions or UTXOs. */
final class ClusterDiagramCache {
    private record Score(long fee, long weight, long virtualSize) { }
    private record Cached(Map<Hash256, Score> scores, List<ClusterLinearization.Chunk> chunks) { }
    private final int maximumTransactions;
    private final BiFunction<Map<Hash256, MempoolEntry>, Set<Hash256>, List<ClusterLinearization.Chunk>> linearizer;
    private Map<Set<Hash256>, Cached> previous = Map.of();
    private java.lang.ref.WeakReference<Map<Hash256, MempoolEntry>> revisionSource;
    private long revision;
    private List<List<ClusterLinearization.Chunk>> revisionView;

    ClusterDiagramCache() { this(65_536, ClusterLinearization::chunks); }

    ClusterDiagramCache(int maximumTransactions,
            BiFunction<Map<Hash256, MempoolEntry>, Set<Hash256>, List<ClusterLinearization.Chunk>> linearizer) {
        if (maximumTransactions < 0) throw new IllegalArgumentException("Negative cluster cache capacity");
        this.maximumTransactions = maximumTransactions;
        this.linearizer = Objects.requireNonNull(linearizer);
    }

    List<List<ClusterLinearization.Chunk>> clusters(Map<Hash256, MempoolEntry> entries) {
        revisionSource = null; revisionView = null;
        return compute(entries, ClusterLinearization.clusters(entries));
    }

    List<List<ClusterLinearization.Chunk>> clusters(Map<Hash256, MempoolEntry> entries,
                                                   List<Set<Hash256>> components, long graphRevision) {
        if (revisionView != null && revisionSource.get() == entries && revision == graphRevision) return revisionView;
        var result = compute(entries, components);
        if (entries.size() <= maximumTransactions) {
            revisionSource = new java.lang.ref.WeakReference<>(entries);
            revision = graphRevision; revisionView = result;
        } else { revisionSource = null; revisionView = null; }
        return result;
    }

    private List<List<ClusterLinearization.Chunk>> compute(Map<Hash256, MempoolEntry> entries, List<Set<Hash256>> components) {
        var next = new HashMap<Set<Hash256>, Cached>();
        var result = new ArrayList<List<ClusterLinearization.Chunk>>();
        int retained = 0;
        for (var cluster : components) {
            var cached = previous.get(cluster);
            if (cached == null || !matches(cached.scores(), entries)) {
                var scores = new HashMap<Hash256, Score>();
                for (var id : cluster) {
                    var entry = entries.get(id);
                    // txid commits to inputs/dependencies; modified fees and sigops-
                    // adjusted sizes are the additional ordering inputs that can change.
                    scores.put(id, new Score(entry.modifiedFee(), entry.adjustedWeight(), entry.virtualSize()));
                }
                cached = new Cached(Map.copyOf(scores), List.copyOf(linearizer.apply(entries, cluster)));
            }
            result.add(cached.chunks());
            if (cluster.size() <= maximumTransactions - retained) {
                next.put(cluster, cached); retained += cluster.size();
            }
        }
        // Merged, split, removed and replaced clusters are discarded every time.
        previous = next;
        return List.copyOf(result);
    }

    private static boolean matches(Map<Hash256, Score> scores, Map<Hash256, MempoolEntry> entries) {
        for (var item : scores.entrySet()) {
            var entry = entries.get(item.getKey());
            var score = item.getValue();
            if (entry == null || entry.modifiedFee() != score.fee() || entry.adjustedWeight() != score.weight()
                    || entry.virtualSize() != score.virtualSize()) return false;
        }
        return true;
    }
}

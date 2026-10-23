package ru.bitcoin.node.rvn.pow;

/**
 * Immutable observation of the lazy DAG cache at a point in time.
 */
public record RvnKawpowDagMetrics(long hits, long misses, long sharedWaits,
                                  long evictions, int cachedItems, int inFlightItems,
                                  long totalItems) {
    public static RvnKawpowDagMetrics snapshot(RvnKawpowLightDag dag) {
        if (dag == null) throw new IllegalArgumentException("dag");
        // These getters are individually synchronized. Metrics are diagnostic,
        // not a transactionally consistent multi-field snapshot.
        return new RvnKawpowDagMetrics(dag.cacheHits(), dag.cacheMisses(),
                dag.sharedWaits(), dag.cacheEvictions(), dag.cachedItemCount(),
                dag.inFlightCount(), dag.itemCount());
    }

    public double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0 : (double) hits / total;
    }
}

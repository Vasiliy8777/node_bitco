package ru.bitcoin.node.rvn.pow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Bounded parallel retrieval of DAG items, preserving input order.
 */
public final class RvnKawpowDagBatch {
    private final RvnKawpowDagReader dag;

    public RvnKawpowDagBatch(RvnKawpowDagReader dag) {
        this.dag = Objects.requireNonNull(dag);
    }

    public List<byte[]> read(long[] indexes, int workers) {
        if (indexes == null) throw new IllegalArgumentException("indexes");
        if (workers < 1 || workers > 64) throw new IllegalArgumentException("workers must be 1..64");
        if (indexes.length > 65_536) throw new IllegalArgumentException("Batch too large");
        for (long index : indexes)
            if (index < 0 || index >= dag.itemCount()) throw new IndexOutOfBoundsException("DAG index " + index);
        if (indexes.length == 0) return List.of();
        int threads = Math.min(workers, indexes.length);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rvn-dag-batch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<byte[]>> futures = new ArrayList<>(indexes.length);
            for (long index : indexes) futures.add(pool.submit(() -> checked(dag.item(index))));
            List<byte[]> result = new ArrayList<>(indexes.length);
            try {
                for (Future<byte[]> f : futures) result.add(f.get());
            } catch (InterruptedException e) {
                for (Future<byte[]> f : futures) f.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DAG batch interrupted", e);
            } catch (ExecutionException e) {
                for (Future<byte[]> f : futures) f.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error err) throw err;
                throw new IllegalStateException("DAG batch failed", cause);
            }
            return List.copyOf(result);
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] checked(byte[] value) {
        if (value == null || value.length != 256)
            throw new IllegalStateException("DAG reader returned non-256-byte item");
        return value.clone();
    }
}

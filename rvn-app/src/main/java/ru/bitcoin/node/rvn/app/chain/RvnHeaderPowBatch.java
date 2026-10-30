package ru.bitcoin.node.rvn.app.chain;

import ru.bitcoin.node.rvn.pow.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded parallel PoW computation only. Contextual admission and persistence
 * remain ordered in RvnMainnetHeaderChain. Each thread owns its epoch cache. */
public final class RvnHeaderPowBatch implements AutoCloseable {
    @FunctionalInterface public interface Factory { RvnPowEngine create() throws RvnPowException; }
    @FunctionalInterface public interface Gpu { List<RvnPowResult> calculate(List<byte[]> raws, long firstHeight) throws IOException; }
    private final Gpu gpu;
    private final ThreadPoolExecutor executor;
    private final ThreadLocal<RvnPowRouter> routers;
    private final Set<Future<?>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public RvnHeaderPowBatch(int workers, Factory factory) {
        this(workers, factory, null);
    }
    public RvnHeaderPowBatch(int workers, Factory factory, Gpu gpu) {
        if (workers < 1 || workers > 8) throw new IllegalArgumentException("RVN header PoW workers must be 1..8");
        Objects.requireNonNull(factory);
        this.gpu = gpu;
        routers = ThreadLocal.withInitial(() -> {
            try { return new RvnPowRouter(RvnPowSchedule.mainnet(), Objects.requireNonNull(factory.create())); }
            catch (RvnPowException e) { throw new IllegalStateException("Cannot create RVN header PoW engine", e); }
        });
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000), Thread.ofPlatform().daemon(true).name("rvn-header-pow-", 0).factory());
    }

    /** One immutable result per input, in original order despite out-of-order
     * completion. On failure/interruption nothing is admitted by this class. */
    public synchronized List<RvnPowResult> calculate(List<byte[]> supplied, long firstHeight) throws IOException {
        if (closed) throw new IOException("RVN header PoW pool closed");
        if (supplied == null || supplied.size() > 2000 || firstHeight < 0)
            throw new IllegalArgumentException("RVN header PoW batch bounds");
        var raws = new ArrayList<byte[]>(supplied.size());
        for (byte[] raw : supplied) {
            if (raw == null || (raw.length != 80 && raw.length != 120)) throw new IllegalArgumentException("RVN PoW header size");
            raws.add(raw.clone());
        }
        if (gpu != null && !raws.isEmpty() && raws.stream().allMatch(raw -> raw.length == 120)) {
            try {
                var results = List.copyOf(gpu.calculate(List.copyOf(raws), firstHeight));
                if (results.size() != raws.size()) throw new IOException("CUDA header result count mismatch");
                if (closed) throw new InterruptedIOException("RVN header verifier closed");
                return results;
            } catch (InterruptedIOException e) { throw e; }
            catch (IOException e) { throw new UncheckedIOException("Local CUDA header verification failed", e); }
        }
        var futures = new ArrayList<Future<RvnPowResult>>(raws.size()); boolean complete = false;
        try {
            for (int i = 0; i < raws.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                byte[] raw = raws.get(i); long height = Math.addExact(firstHeight, i);
                var future = executor.submit(() -> routers.get().calculate(height, raw));
                futures.add(future); active.add(future);
                if (closed) future.cancel(true);
            }
            var results = new ArrayList<RvnPowResult>(futures.size());
            for (var future : futures) results.add(Objects.requireNonNull(future.get()));
            complete = true; return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("RVN parallel header verification interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (closed) throw new IOException("RVN header PoW pool closed during calculation", cause);
            if (cause instanceof RvnPowException) throw new SecurityException("RVN parallel PoW calculation failed", cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("RVN parallel PoW worker failed", cause);
        } catch (RejectedExecutionException | CancellationException e) {
            throw new IOException("RVN header PoW pool unavailable", e);
        } finally {
            if (!complete) for (var future : futures) future.cancel(true);
            active.removeAll(futures);
            executor.purge();
        }
    }

    @Override public void close() {
        closed = true;
        for (var future : active) future.cancel(true);
        for (var task : executor.shutdownNow()) if (task instanceof Future<?> future) future.cancel(false);
    }
}

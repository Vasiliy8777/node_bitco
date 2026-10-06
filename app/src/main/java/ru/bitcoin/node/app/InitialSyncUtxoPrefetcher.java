package ru.bitcoin.node.app;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded optional read hints; results never participate in consensus decisions. */
final class InitialSyncUtxoPrefetcher implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(InitialSyncUtxoPrefetcher.class.getName());
    private final ThreadPoolExecutor worker;
    private final Consumer<List<OutPoint>> warmer;

    InitialSyncUtxoPrefetcher(Consumer<List<OutPoint>> warmer) {
        this.warmer = warmer;
        worker = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "ibd-utxo-prefetch");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
        worker.allowCoreThreadTimeOut(true);
    }

    void offer(List<OutPoint> inputs) {
        if (inputs.isEmpty() || worker.isShutdown()) return;
        var bounded = List.copyOf(inputs.subList(0, Math.min(inputs.size(), 8192)));
        worker.execute(() -> {
            try { warmer.accept(bounded); }
            catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "UTXO cache hint failed; normal validation reads remain authoritative", failure);
            }
        });
    }

    @Override public void close() { worker.shutdownNow(); }
}

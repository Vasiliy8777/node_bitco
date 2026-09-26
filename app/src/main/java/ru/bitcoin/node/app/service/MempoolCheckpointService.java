package ru.bitcoin.node.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bitcoin.node.app.NodeValidationService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Periodically checkpoints a dirty persistent mempool without adding I/O to transaction admission. */
public final class MempoolCheckpointService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MempoolCheckpointService.class);

    private final Duration interval;
    private final BooleanSupplier dirty;
    private final Runnable flush;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService executor;

    public MempoolCheckpointService(NodeValidationService validationService, Duration interval) {
        this(interval,
                Objects.requireNonNull(validationService, "validationService")::isMempoolPersistenceDirty,
                validationService::flushPersistentMempool);
    }

    MempoolCheckpointService(Duration interval, BooleanSupplier dirty, Runnable flush) {
        this.interval = Objects.requireNonNull(interval, "interval");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.flush = Objects.requireNonNull(flush, "flush");
        if (interval.isNegative()) throw new IllegalArgumentException("Mempool checkpoint interval must not be negative");
    }

    public synchronized void start() {
        if (interval.isZero() || !started.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mempool-checkpoint");
            thread.setDaemon(true);
            return thread;
        });
        long delayMillis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::checkpoint, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void checkpoint() {
        try {
            if (dirty.getAsBoolean()) flush.run();
        } catch (RuntimeException exception) {
            // A transient checkpoint failure must not terminate the periodic task or the node.
            log.warn("Failed to checkpoint persistent mempool; will retry", exception);
        }
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService current = executor;
        executor = null;
        if (current == null) return;
        current.shutdownNow();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Mempool checkpoint worker did not terminate within timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}

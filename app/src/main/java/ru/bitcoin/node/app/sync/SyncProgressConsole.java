package ru.bitcoin.node.app.sync;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight one-line IBD progress display. Hot sync paths only publish primitive counters. */
public final class SyncProgressConsole {
    private static final long SAMPLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final SyncProgressConsole INSTANCE = new SyncProgressConsole(System.out);

    private final PrintStream out;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile Phase phase = Phase.IDLE;
    private volatile long current;
    private volatile long target;
    private volatile long blockTimestampSeconds;

    private long sampledCurrent;
    private long sampledNanos;
    private int lastWidth;

    private SyncProgressConsole(PrintStream out) {
        this.out = out;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "ibd-progress-console");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public static void headers(long current, long target) {
        INSTANCE.publish(Phase.HEADERS, current, target, 0);
    }

    /** Publishes and immediately renders the authoritative final header height. */
    public static void headersComplete(long current, long target) {
        INSTANCE.completeHeaders(current, target);
    }

    public static void blocks(long current, long target, long timestampSeconds) {
        INSTANCE.publish(Phase.BLOCKS, current, target, timestampSeconds);
    }

    /** Stop sampling after the requested chain is committed, rather than printing 100% at 0/s. */
    public static void blocksComplete(long height) {
        INSTANCE.completeBlocks(height);
    }

    public static void clear() {
        INSTANCE.phase = Phase.IDLE;
    }

    private synchronized void publish(Phase newPhase, long newCurrent, long newTarget, long newBlockTimestampSeconds) {
        long normalizedCurrent = Math.max(0L, newCurrent);
        phase = newPhase;
        current = normalizedCurrent;
        target = Math.max(0L, newTarget);
        blockTimestampSeconds = newBlockTimestampSeconds;
        startOnce();
    }

    private void startOnce() {
        if (started.compareAndSet(false, true)) {
            sampledCurrent = current;
            sampledNanos = System.nanoTime();
            executor.scheduleAtFixedRate(this::renderSafely, 0L, 1L, TimeUnit.SECONDS);
        }
    }

    private void renderSafely() {
        try {
            render();
        } catch (RuntimeException ignored) {
            // Progress output must never affect node synchronization.
        }
    }

    private synchronized void completeHeaders(long finalCurrent, long finalTarget) {
        phase = Phase.HEADERS;
        current = Math.max(0L, finalCurrent);
        target = Math.max(0L, finalTarget);
        blockTimestampSeconds = 0L;
        startOnce();
        render();
        out.println();
        out.flush();
        lastWidth = 0;
        phase = Phase.IDLE;
    }

    private synchronized void completeBlocks(long height) {
        phase = Phase.IDLE;
        String line = "BLK synchronized at height " + height;
        out.print("\r" + line + " ".repeat(Math.max(0, lastWidth - line.length())));
        out.println();
        out.flush();
        lastWidth = 0;
    }

    private synchronized void render() {
        Phase snapshotPhase = phase;
        if (snapshotPhase == Phase.IDLE) {
            return;
        }

        long snapshotCurrent = current;
        long snapshotTarget = target;
        long now = System.nanoTime();
        long elapsed = Math.max(1L, now - sampledNanos);
        long delta = Math.max(0L, snapshotCurrent - sampledCurrent);
        long rate = Math.round(delta * (double) SAMPLE_NANOS / elapsed);
        double percent = snapshotTarget <= 0L
                ? 0.0
                : Math.min(100.0, snapshotCurrent * 100.0 / snapshotTarget);

        String line = snapshotPhase == Phase.HEADERS
                ? String.format(Locale.ROOT, "HDR %.1f%% %d/%d %d/s", percent, snapshotCurrent, snapshotTarget, rate)
                : String.format(Locale.ROOT, "BLK %.1f%% %d/%d %d/s %d", percent, snapshotCurrent, snapshotTarget, rate,
                Instant.ofEpochSecond(blockTimestampSeconds).atZone(ZoneOffset.UTC).getYear());

        int padding = Math.max(0, lastWidth - line.length());
        out.print("\r" + line + " ".repeat(padding));
        out.flush();
        lastWidth = line.length();
        sampledCurrent = snapshotCurrent;
        sampledNanos = now;
    }

    private enum Phase { IDLE, HEADERS, BLOCKS }
}

package ru.bitcoin.node.rvn.app.mining;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import java.util.Objects;

/** Starts only after all ApplicationRunners (including real CUDA self-tests).
 * The backend rechecks validation, peer and parent before and after every batch. */
public final class RvnMiningService implements SmartLifecycle, ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(RvnMiningService.class);
    private final RvnSoloMining miner;
    private final AutoCloseable worker;
    private volatile boolean running;
    private boolean ready;
    private Thread thread;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public RvnMiningService(RvnSoloMining miner, AutoCloseable worker) {
        this.miner = Objects.requireNonNull(miner); this.worker = Objects.requireNonNull(worker);
    }
    @Override public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("RVN miner requires a new self-tested worker after shutdown");
        running = true; launchIfReady();
    }
    @Override public synchronized void onApplicationEvent(ApplicationReadyEvent event) { ready = true; launchIfReady(); }
    private void launchIfReady() {
        if (running && ready && thread == null) {
            thread = Thread.ofPlatform().name("rvn-solo-miner").unstarted(this::run);
            thread.start();
        }
    }
    private void run() {
        RvnSoloMining.State previous = null;
        long lastReport = System.nanoTime(), hashes = 0, accepted = 0;
        long lastStats = lastReport, intervalHashes = 0, sessionHashes = 0;
        try {
            while (running) {
                var progress = miner.advance();
                hashes += progress.hashes(); accepted += progress.accepted();
                intervalHashes += progress.hashes(); sessionHashes += progress.hashes();
                long now = System.nanoTime();
                // Keep a wall-clock measurement window across template refreshes,
                // new chain tips and idle periods. State logs reset independently.
                long statsNanos = now - lastStats;
                if (statsNanos >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30) || progress.accepted() > 0) {
                    double megaHashesPerSecond = statsNanos > 0 ? intervalHashes * 1000.0 / statsNanos : 0;
                    var stats = miner.statistics();
                    log.info("RVN mining stats: hashrate={} MH/s, targetCandidatesSession={}, staleCandidatesSession={}, minedBlocksSession={}, relayedBlocksSession={}, admissionFailuresSession={}, relayFailuresSession={}, hashesSession={}, intervalMs={}",
                            String.format(java.util.Locale.ROOT, "%.3f", megaHashesPerSecond), stats.targetCandidates(),
                            stats.staleCandidates(), stats.locallyAcceptedBlocks(), stats.relayedBlocks(),
                            stats.admissionFailures(), stats.relayFailures(), sessionHashes,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(statsNanos));
                    lastStats = now; intervalHashes = 0;
                }
                if (progress.state() != previous || now - lastReport >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                    if (progress.state() == RvnSoloMining.State.WAITING)
                        log.info("RVN solo miner: WAITING; {}", miner.waitingReason());
                    else log.info("RVN solo miner: {}; hashes={}, accepted={}, elapsedMs={}", progress.state(), hashes, accepted,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - lastReport));
                    previous = progress.state();
                    lastReport = now; hashes = 0; accepted = 0;
                }
                if (progress.state() == RvnSoloMining.State.WAITING) Thread.sleep(25);
                else if (progress.state() == RvnSoloMining.State.STALE && progress.hashes() == 0)
                    Thread.sleep(1); // Avoid spinning if a job changes before any GPU work starts.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A broken worker/validator must not produce further work. Restart
            // requires new self-tests; durable relay markers survive this exit.
            if (running) log.error("RVN solo miner stopped after a local failure; session statistics=" + miner.statistics(), e);
        } finally { running = false; closeWorker(); }
    }
    @Override public void stop() {
        Thread stopping;
        synchronized (this) { running = false; stopping = thread; }
        if (stopping != null) stopping.interrupt();
        closeWorker(); // Also abort a pending CUDA preparation/search.
        if (stopping != null && stopping != Thread.currentThread()) {
            try { stopping.join(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private void closeWorker() {
        if (!closed.compareAndSet(false, true)) return;
        try { worker.close(); }
        catch (Exception e) { log.warn("RVN CUDA worker close failed", e); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 50; }
}

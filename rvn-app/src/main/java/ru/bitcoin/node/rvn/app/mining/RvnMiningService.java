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
        try {
            while (running) {
                var progress = miner.advance();
                hashes += progress.hashes(); accepted += progress.accepted();
                long now = System.nanoTime();
                if (progress.state() != previous || now - lastReport >= java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                    if (progress.state() == RvnSoloMining.State.WAITING)
                        log.info("RVN solo miner: WAITING; {}", miner.waitingReason());
                    else log.info("RVN solo miner: {}; hashes={}, accepted={}, elapsedMs={}", progress.state(), hashes, accepted,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - lastReport));
                    previous = progress.state();
                    lastReport = now; hashes = 0; accepted = 0;
                }
                if (progress.state() == RvnSoloMining.State.WAITING || progress.state() == RvnSoloMining.State.STALE)
                    Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A broken worker/validator must not produce further work. Restart
            // requires new self-tests; durable relay markers survive this exit.
            if (running) log.error("RVN solo miner stopped after a local failure", e);
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

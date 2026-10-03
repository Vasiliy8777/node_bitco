package ru.bitcoin.node.consensus.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Core-style bounded script verification queue. Consensus state mutation stays on the caller. */
public final class ScriptCheckQueue {
    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "script-check"); t.setDaemon(true); return t;
    });
    private ScriptCheckQueue() {}

    public static void run(List<? extends Runnable> checks) {
        if (checks == null) throw new IllegalArgumentException("checks must not be null");
        if (checks.size() <= 1 || THREADS == 1) { for (Runnable r : checks) r.run(); return; }
        List<Future<?>> futures = new ArrayList<>(checks.size());
        for (Runnable check : checks) futures.add(EXECUTOR.submit(check));
        RuntimeException first = null;
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new TransactionValidationException("Script verification interrupted"); }
            catch (ExecutionException e) {
                Throwable c = e.getCause();
                RuntimeException failure = c instanceof RuntimeException r ? r : new TransactionValidationException("Script verification failed: " + c);
                if (first == null) first = failure; else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }
}

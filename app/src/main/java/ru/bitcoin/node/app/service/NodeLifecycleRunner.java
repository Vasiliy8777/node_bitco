package ru.bitcoin.node.app.service;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NodeLifecycleRunner
        implements AutoCloseable {

    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS =
            10_000L;

    private final NodeLifecycle lifecycle;

    private final AtomicBoolean started =
            new AtomicBoolean();

    private final long shutdownTimeoutMillis;

    private Thread worker;

    public NodeLifecycleRunner(
            NodeLifecycle lifecycle
    ) {
        this(
                lifecycle,
                DEFAULT_SHUTDOWN_TIMEOUT_MILLIS
        );
    }

    NodeLifecycleRunner(
            NodeLifecycle lifecycle,
            long shutdownTimeoutMillis
    ) {
        this.lifecycle =
                Objects.requireNonNull(
                        lifecycle,
                        "lifecycle"
                );

        if (shutdownTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "shutdownTimeoutMillis must be positive"
            );
        }

        this.shutdownTimeoutMillis =
                shutdownTimeoutMillis;
    }

    public synchronized void start() {

        if (!started.compareAndSet(
                false,
                true
        )) {
            throw new IllegalStateException(
                    "Node lifecycle runner already started"
            );
        }

        worker =
                Thread.ofPlatform()
                        .name(
                                "bitcoin-node-lifecycle"
                        )
                        .daemon(false)
                        .start(
                                this::run
                        );
    }

    private void run() {

        try {

            lifecycle.start();

        } catch (IOException | RuntimeException ignored) {

            /*
             * Lifecycle implementation owns its failure state.
             */
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void close()
            throws IOException {

        IOException closeFailure =
                null;

        try {

            lifecycle.close();

        } catch (IOException exception) {

            closeFailure =
                    exception;
        }

        Thread currentWorker;

        synchronized (this) {
            currentWorker =
                    worker;
        }

        if (currentWorker != null
                && currentWorker != Thread.currentThread()) {

            try {

                currentWorker.join(
                        shutdownTimeoutMillis
                );

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                IOException interrupted =
                        new IOException(
                                "Interrupted while waiting for "
                                        + "node lifecycle worker to stop",
                                exception
                        );

                if (closeFailure != null) {
                    interrupted.addSuppressed(
                            closeFailure
                    );
                }

                throw interrupted;
            }

            if (currentWorker.isAlive()) {

                IOException timeout =
                        new IOException(
                                "Node lifecycle worker did not stop "
                                        + "within "
                                        + shutdownTimeoutMillis
                                        + " ms"
                        );

                if (closeFailure != null) {
                    timeout.addSuppressed(
                            closeFailure
                    );
                }

                throw timeout;
            }
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
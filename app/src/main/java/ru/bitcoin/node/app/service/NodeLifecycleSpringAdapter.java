package ru.bitcoin.node.app.service;

import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NodeLifecycleSpringAdapter
        implements SmartLifecycle {

    private final NodeLifecycleRunner runner;

    private final AtomicBoolean running =
            new AtomicBoolean();

    public NodeLifecycleSpringAdapter(
            NodeLifecycleRunner runner
    ) {
        this.runner =
                Objects.requireNonNull(
                        runner,
                        "runner"
                );
    }

    @Override
    public void start() {

        if (!running.compareAndSet(
                false,
                true
        )) {
            return;
        }

        try {

            runner.start();

        } catch (RuntimeException exception) {

            running.set(
                    false
            );

            throw exception;
        }
    }

    @Override
    public void stop() {

        if (!running.compareAndSet(
                true,
                false
        )) {
            return;
        }

        try {

            runner.close();

        } catch (IOException exception) {

            throw new IllegalStateException(
                    "Unable to stop Bitcoin node",
                    exception
            );
        }
    }

    @Override
    public void stop(
            Runnable callback
    ) {

        Objects.requireNonNull(
                callback,
                "callback"
        );

        try {

            stop();

        } finally {

            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
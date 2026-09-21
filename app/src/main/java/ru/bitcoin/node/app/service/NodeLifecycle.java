package ru.bitcoin.node.app.service;

import java.io.IOException;

public interface NodeLifecycle
        extends AutoCloseable {

    void start()
            throws IOException;

    NodeLifecycleState state();

    boolean isRunning();

    @Override
    void close()
            throws IOException;
}
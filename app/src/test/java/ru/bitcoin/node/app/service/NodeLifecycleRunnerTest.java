package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NodeLifecycleRunnerTest {

    @Test
    void shouldStartLifecycleOnBackgroundThread()
            throws Exception {

        CountDownLatch entered =
                new CountDownLatch(1);

        CountDownLatch release =
                new CountDownLatch(1);

        AtomicBoolean closed =
                new AtomicBoolean();

        NodeLifecycle lifecycle =
                new NodeLifecycle() {

                    @Override
                    public void start()
                            throws IOException {

                        entered.countDown();

                        try {

                            if (!release.await(
                                    5,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IOException(
                                        "Timed out waiting for test release"
                                );
                            }

                        } catch (InterruptedException exception) {

                            Thread.currentThread()
                                    .interrupt();

                            throw new IOException(
                                    "Interrupted",
                                    exception
                            );
                        }
                    }

                    @Override
                    public NodeLifecycleState state() {
                        return NodeLifecycleState.STARTING;
                    }

                    @Override
                    public boolean isRunning() {
                        return false;
                    }

                    @Override
                    public void close() {
                        closed.set(
                                true
                        );

                        release.countDown();
                    }
                };

        NodeLifecycleRunner runner =
                new NodeLifecycleRunner(
                        lifecycle
                );

        runner.start();

        assertTrue(
                entered.await(
                        1,
                        TimeUnit.SECONDS
                )
        );

        /*
         * If start() were synchronous,
         * runner.start() could not have returned while
         * lifecycle.start() is blocked on release.
         */
        assertTrue(
                runner.isStarted()
        );

        runner.close();

        assertTrue(
                closed.get()
        );
    }

    @Test
    void shouldRejectSecondStart() {

        NodeLifecycle lifecycle =
                new NoOpLifecycle();

        NodeLifecycleRunner runner =
                new NodeLifecycleRunner(
                        lifecycle
                );

        runner.start();

        assertThrows(
                IllegalStateException.class,
                runner::start
        );
    }

    @Test
    void shouldWaitForWorkerToFinishDuringClose()
            throws Exception {

        CountDownLatch started =
                new CountDownLatch(1);

        CountDownLatch release =
                new CountDownLatch(1);

        CountDownLatch finished =
                new CountDownLatch(1);

        NodeLifecycle lifecycle =
                new NodeLifecycle() {

                    @Override
                    public void start()
                            throws IOException {

                        started.countDown();

                        try {

                            release.await();

                        } catch (InterruptedException exception) {

                            Thread.currentThread()
                                    .interrupt();

                            throw new IOException(
                                    "Interrupted",
                                    exception
                            );

                        } finally {

                            finished.countDown();
                        }
                    }

                    @Override
                    public NodeLifecycleState state() {
                        return NodeLifecycleState.STARTING;
                    }

                    @Override
                    public boolean isRunning() {
                        return false;
                    }

                    @Override
                    public void close() {
                        release.countDown();
                    }
                };

        NodeLifecycleRunner runner =
                new NodeLifecycleRunner(
                        lifecycle,
                        1_000
                );

        runner.start();

        assertTrue(
                started.await(
                        1,
                        TimeUnit.SECONDS
                )
        );

        runner.close();

        assertEquals(
                0L,
                finished.getCount()
        );
    }

    private static final class NoOpLifecycle
            implements NodeLifecycle {

        @Override
        public void start() {
        }

        @Override
        public NodeLifecycleState state() {
            return NodeLifecycleState.RUNNING;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
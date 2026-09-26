package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MempoolCheckpointServiceTest {

    @Test
    void checkpointsOnlyDirtyStateAndStopsCleanly() throws Exception {
        AtomicBoolean dirty = new AtomicBoolean(false);
        AtomicInteger flushes = new AtomicInteger();
        CountDownLatch flushed = new CountDownLatch(1);
        var service = new MempoolCheckpointService(Duration.ofMillis(10), dirty::get, () -> {
            flushes.incrementAndGet();
            dirty.set(false);
            flushed.countDown();
        });
        service.start();
        Thread.sleep(35);
        assertEquals(0, flushes.get());
        dirty.set(true);
        assertTrue(flushed.await(2, TimeUnit.SECONDS));
        assertEquals(1, flushes.get());
        service.close();
        dirty.set(true);
        Thread.sleep(35);
        assertEquals(1, flushes.get());
    }

    @Test
    void retriesAfterCheckpointFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        try (var service = new MempoolCheckpointService(Duration.ofMillis(10), () -> true, () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("simulated write failure");
            succeeded.countDown();
        })) {
            service.start();
            assertTrue(succeeded.await(2, TimeUnit.SECONDS));
            assertTrue(attempts.get() >= 2);
        }
    }

    @Test
    void zeroIntervalDisablesPeriodicCheckpoint() throws Exception {
        AtomicInteger flushes = new AtomicInteger();
        try (var service = new MempoolCheckpointService(Duration.ZERO, () -> true, flushes::incrementAndGet)) {
            service.start();
            Thread.sleep(30);
        }
        assertEquals(0, flushes.get());
    }
}

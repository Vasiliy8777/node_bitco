package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class InitialSyncUtxoPrefetcherTest {
    @Test
    void keepsOnlyLatestQueuedHintAndBoundsItsSize() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        var received = new java.util.concurrent.CopyOnWriteArrayList<List<OutPoint>>();
        var point = new OutPoint(new Hash256(new byte[32]), new UInt32(0));
        var latest = new OutPoint(new Hash256(new byte[32]), new UInt32(100));
        try (var warmer = new InitialSyncUtxoPrefetcher(inputs -> {
            received.add(inputs);
            started.countDown();
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finished.countDown();
        })) {
            warmer.offer(Collections.nCopies(9000, point));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) warmer.offer(List.of(point));
            warmer.offer(List.of(latest));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(2, received.size());
            assertEquals(8192, received.getFirst().size());
            assertEquals(List.of(latest), received.getLast());
        } finally { release.countDown(); }
    }
}

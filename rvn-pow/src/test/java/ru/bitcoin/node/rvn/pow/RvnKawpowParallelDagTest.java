package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowParallelDagTest {
    private RvnKawpowLightDag fixture(int capacity) {
        return new RvnKawpowLightDag(RvnKawpowCache.build(new byte[32], 17), 128L * 32, capacity);
    }

    @Test
    void concurrentSameItemIsSafeAndDefensive() throws Exception {
        var dag = fixture(3);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<Future<byte[]>>();
            for (int i = 0; i < 16; i++)
                futures.add(pool.submit(() -> {
                    start.await();
                    return dag.item(4);
                }));
            start.countDown();
            byte[] expected = futures.get(0).get(10, TimeUnit.SECONDS);
            for (var f : futures) assertArrayEquals(expected, f.get(10, TimeUnit.SECONDS));
            assertEquals(1, dag.cacheMisses());
            expected[0] ^= 0x55;
            assertFalse(Arrays.equals(expected, dag.item(4)));
            assertEquals(0, dag.inFlightCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictionsAreBoundedAndClearWorks() {
        var dag = fixture(2);
        dag.item(0);
        dag.item(1);
        dag.item(0);
        dag.item(2);
        assertEquals(2, dag.cachedItemCount());
        assertEquals(1, dag.cacheEvictions());
        assertEquals(1, dag.cacheHits());
        assertEquals(3, dag.cacheMisses());
        dag.item(1);
        assertEquals(4, dag.cacheMisses());
        dag.clearCache();
        assertEquals(0, dag.cachedItemCount());
    }

    @Test
    void zeroCapacityDoesNotRetainItems() {
        var dag = fixture(0);
        assertArrayEquals(dag.item(2), dag.item(2));
        assertEquals(0, dag.cachedItemCount());
        assertEquals(2, dag.cacheMisses());
    }

    @Test
    void diagnosticMetrics() {
        var dag = fixture(2);
        assertEquals(0, RvnKawpowDagMetrics.snapshot(dag).hitRatio());
        dag.item(0);
        dag.item(0);
        var metrics = RvnKawpowDagMetrics.snapshot(dag);
        assertEquals(1, metrics.hits());
        assertEquals(1, metrics.misses());
        assertEquals(.5, metrics.hitRatio());
        assertEquals(32, metrics.totalItems());
    }
}

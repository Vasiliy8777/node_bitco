package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnLightDagAccessTest {
    private static RvnKawpowLightDag dag(int capacity) {
        return new RvnKawpowLightDag(RvnKawpowCache.build(new byte[32], 17), 256 * 5L, capacity);
    }

    @Test
    void onDemandMatchesExistingDatasetBuilder() {
        var cache = RvnKawpowCache.build(new byte[32], 17);
        var dag = new RvnKawpowLightDag(cache, 256 * 5L, 2);
        assertEquals(5, dag.itemCount());
        for (int i = 0; i < 5; i++)
            assertArrayEquals(RvnKawpowDatasetItem.dagItem(cache, i), dag.item(i));
        assertEquals(2, dag.cachedItemCount());
        assertEquals(5, dag.cacheMisses());
    }

    @Test
    void lruEvictsLeastRecentlyUsedAndDefensivelyCopies() {
        var dag = dag(2);
        byte[] original = dag.item(0);
        dag.item(1);
        byte[] mutation = dag.item(0);
        mutation[0] ^= 0x7f;
        assertArrayEquals(original, dag.item(0));
        assertEquals(2, dag.cacheHits());
        dag.item(2);
        assertEquals(2, dag.cachedItemCount());
        long misses = dag.cacheMisses();
        dag.item(1);
        assertEquals(misses + 1, dag.cacheMisses());
        dag.clearCache();
        assertEquals(0, dag.cachedItemCount());
    }

    @Test
    void uncachedModeAndUnsignedIndexing() {
        var dag = dag(0);
        var lookup = new RvnKawpowDagLookup(dag);
        assertEquals(4294967295L % 5L, lookup.normalizeUnsigned(-1));
        byte[] item = lookup.itemUnsigned(-1);
        assertArrayEquals(dag.item(4294967295L % 5L), item);
        assertEquals(RvnKawpowWords.readLE(item, 124), lookup.wordUnsigned(-1, 31));
        assertEquals(0, dag.cacheHits());
        assertEquals(0, dag.cachedItemCount());
    }

    @Test
    void rejectsInvalidBoundsAndProviders() {
        assertThrows(IllegalArgumentException.class, () -> new RvnKawpowLightDag(
                RvnKawpowCache.build(new byte[32], 17), 129, 1));
        assertThrows(IllegalArgumentException.class, () -> dag(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> dag(1).item(5));
        assertThrows(IndexOutOfBoundsException.class, () -> dag(1).item(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> new RvnKawpowDagLookup(dag(1)).wordUnsigned(0, 64));
        assertThrows(IllegalArgumentException.class, () -> new RvnKawpowDagLookup(new RvnKawpowDagReader() {
            public long itemCount() {
                return 0;
            }

            public byte[] item(long index) {
                return new byte[256];
            }
        }));
        var malformed = new RvnKawpowDagLookup(new RvnKawpowDagReader() {
            public long itemCount() {
                return 1;
            }

            public byte[] item(long index) {
                return new byte[127];
            }
        });
        assertThrows(IllegalStateException.class, () -> malformed.itemUnsigned(0));
    }

    @Test
    void concurrentReadsAreStable() throws Exception {
        var dag = dag(3);
        byte[] expected = dag.item(2);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<Future<byte[]>>();
            for (int i = 0; i < 16; i++) jobs.add(pool.submit(() -> dag.item(2)));
            for (var job : jobs) assertArrayEquals(expected, job.get(10, TimeUnit.SECONDS));
            assertTrue(dag.cacheHits() >= 16);
        } finally {
            pool.shutdownNow();
        }
    }
}

package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RvnStages101To110Test {
    @Test
    void epochZeroKnownSizes() {
        assertEquals(16776896L, RvnKawpowDatasetSizes.cacheBytes(0));
        assertEquals(1073739904L, RvnKawpowDatasetSizes.datasetBytes(0));
        for (long e = 0; e < 6; e++) {
            long cache = RvnKawpowDatasetSizes.cacheBytes(e);
            long dag = RvnKawpowDatasetSizes.datasetBytes(e);
            assertEquals(0, cache % 64);
            assertEquals(0, dag % 128);
            assertTrue(RvnKawpowDatasetSizes.isPrime(cache / 64));
            assertTrue(RvnKawpowDatasetSizes.isPrime(dag / 128));
            assertTrue(cache < RvnKawpowDatasetSizes.CACHE_INIT + e * RvnKawpowDatasetSizes.CACHE_GROWTH);
            assertTrue(dag < RvnKawpowDatasetSizes.DATASET_INIT + e * RvnKawpowDatasetSizes.DATASET_GROWTH);
        }
    }

    @Test
    void epochSeedUsesLegacyKeccak256() {
        byte[] zero = new byte[32];
        assertArrayEquals(zero, RvnKawpowEpochSeed.forEpoch(0));
        assertArrayEquals(RvnKeccak256.digest(zero), RvnKawpowEpochSeed.forEpoch(1));
        assertArrayEquals(RvnKeccak256.digest(RvnKeccak256.digest(zero)),
                RvnKawpowEpochSeed.forHeight(15000));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowEpochSeed.forEpoch(-1));
    }

    @Test
    void smallCacheIsDeterministicAndDefensive() {
        byte[] seed = RvnKawpowEpochSeed.forEpoch(0);
        var a = RvnKawpowCache.build(seed, 17);
        var b = RvnKawpowCache.build(seed, 17);
        assertEquals(17, a.size());
        for (int i = 0; i < 17; i++) assertArrayEquals(a.row(i), b.row(i));
        byte[] before = a.row(0);
        byte[] mutated = a.row(0);
        mutated[0] ^= 42;
        assertArrayEquals(before, a.row(0));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowCache.build(seed, 1));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowCache.build(new byte[31], 17));
    }

    @Test
    void lightDatasetNodesAndItems() {
        var cache = RvnKawpowCache.build(new byte[32], 17);
        byte[] zero = RvnKawpowDatasetItem.node(cache, 0);
        assertEquals(64, zero.length);
        assertArrayEquals(zero, RvnKawpowDatasetItem.node(cache, 0));
        assertFalse(Arrays.equals(zero, RvnKawpowDatasetItem.node(cache, 1)));
        byte[] item = RvnKawpowDatasetItem.dagItem(cache, 0);
        assertEquals(128, item.length);
        assertArrayEquals(zero, Arrays.copyOfRange(item, 0, 64));
        assertArrayEquals(RvnKawpowDatasetItem.node(cache, 1), Arrays.copyOfRange(item, 64, 128));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowDatasetItem.node(cache, -1));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowDatasetItem.dagItem(cache, -1));
    }

    @Test
    void overflowAndInvalidInputs() {
        assertThrows(ArithmeticException.class, () -> RvnKawpowDatasetSizes.datasetBytes(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowDatasetSizes.cacheBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowEpochSeed.forEpoch(1_000_001));
    }
}

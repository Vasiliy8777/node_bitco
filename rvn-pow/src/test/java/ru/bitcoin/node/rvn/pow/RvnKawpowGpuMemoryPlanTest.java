package ru.bitcoin.node.rvn.pow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RvnKawpowGpuMemoryPlanTest {
    @Test
    void epochBoundariesAndOneDatasetBudget() {
        var first = RvnKawpowGpuMemoryPlan.calculate(7499, 12L << 30, 11L << 30, 1L << 30);
        var next = RvnKawpowGpuMemoryPlan.calculate(7500, 12L << 30, 11L << 30, 1L << 30);
        assertEquals(0, first.epoch()); assertEquals(1, next.epoch());
        assertTrue(next.dagBytes() > first.dagBytes()); assertTrue(next.fits());
        var illustrative = RvnKawpowGpuMemoryPlan.calculate(5_000_000, 12L << 30, 11L << 30, 1L << 30);
        assertTrue(illustrative.fits()); assertTrue(2 * illustrative.dagBytes() > (12L << 30));
        assertTrue(illustrative.lightCacheBytes() < (128L << 20));
    }

    @Test
    void actualFreeMemoryAndReserveGateAllocationWithoutSpill() {
        long dag = RvnKawpowDatasetSizes.datasetBytes(0), reserve = 1L << 30;
        assertDoesNotThrow(() -> RvnKawpowGpuMemoryPlan.calculate(0, 12L << 30, dag + reserve, reserve).requireFits());
        var tooSmall = RvnKawpowGpuMemoryPlan.calculate(0, 12L << 30, dag + reserve - 1, reserve);
        assertFalse(tooSmall.fits()); assertThrows(IllegalStateException.class, tooSmall::requireFits);
        assertThrows(IllegalArgumentException.class, () -> RvnKawpowGpuMemoryPlan.calculate(0, 100, 101, 0));
    }
}

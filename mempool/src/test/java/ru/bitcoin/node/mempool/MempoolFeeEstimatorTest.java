package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MempoolFeeEstimatorTest {
    @TempDir Path directory;
    private static final Hash256 TIP = new Hash256(new byte[32]);

    @Test void requiresSixBlocksAndAtLeastSeventyFivePercentCoverageForBusyWindow() {
        var estimator = new MempoolFeeEstimator();
        for (int height = 1; height <= 5; height++) estimator.connected(new MempoolFeeEstimator.BlockStats(height, 600_000, 800_000), TIP);
        assertNotNull(estimator.healthError());
        estimator.connected(new MempoolFeeEstimator.BlockStats(6, 600_000, 800_000), TIP);
        assertNull(estimator.healthError());
        estimator.connected(new MempoolFeeEstimator.BlockStats(7, 599_999, 800_000), TIP);
        assertNotNull(estimator.healthError());
        estimator.connected(new MempoolFeeEstimator.BlockStats(7, 600_000, 800_000), TIP);
        assertNull(estimator.healthError());
        estimator.connected(new MempoolFeeEstimator.BlockStats(10, 1, 1), TIP);
        assertEquals(1, estimator.statistics().size());
    }

    @Test void percentilesSparseFloorAndSevenSecondCacheAreTipBound() {
        var estimator = new MempoolFeeEstimator();
        for (int height = 1; height <= 6; height++) estimator.connected(new MempoolFeeEstimator.BlockStats(height, 0, 0), TIP);
        var chunks = List.of(new MempoolFeeEstimator.FeeChunk(5_000_000, 500_000), new MempoolFeeEstimator.FeeChunk(1_250_000, 250_000));
        assertEquals(10_000L, estimator.estimate(TIP, 0, true, 100, () -> chunks).satoshisPerKvB());
        assertEquals(5_000L, estimator.estimate(TIP, 1, false, 100, () -> { throw new AssertionError("cache miss"); }).satoshisPerKvB());
        assertEquals(100L, estimator.estimate(TIP, 8_000_000_000L, true, 100, List::of).satoshisPerKvB());
        byte[] other = new byte[32]; other[0] = 1;
        assertEquals(10_000L, estimator.estimate(new Hash256(other), 8_000_000_001L, true, 100, () -> chunks).satoshisPerKvB());
    }

    @Test void persistenceRejectsWrongTipAndTruncationWithoutPartialPublication() throws Exception {
        var estimator = new MempoolFeeEstimator();
        for (int height = 1; height <= 6; height++) estimator.connected(new MempoolFeeEstimator.BlockStats(height, 0, 0), TIP);
        var file = directory.resolve("fees/mempool_policy_estimator.dat"); estimator.save(file);
        var restored = new MempoolFeeEstimator();
        assertTrue(restored.load(file, 6, TIP)); assertNull(restored.healthError());
        assertFalse(restored.load(file, 7, TIP)); assertEquals(6, restored.statistics().size());
        Files.write(file, new byte[]{1}); assertFalse(restored.load(file, 6, TIP));
        assertEquals(6, restored.statistics().size());
    }
}

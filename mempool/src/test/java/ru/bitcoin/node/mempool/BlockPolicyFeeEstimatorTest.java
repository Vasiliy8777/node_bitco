package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.Hash256;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockPolicyFeeEstimatorTest {
    @TempDir Path directory;

    @Test void learnsConfirmationDelaysAndPersistsAcrossRestart() throws Exception {
        var estimator = new BlockPolicyFeeEstimator();
        assertNull(estimator.estimate(2, false).satoshisPerKvB());
        assertEquals(0, estimator.estimate(2, false).blocks());
        var pending = new ArrayList<List<Hash256>>();
        for (int i = 0; i < 10; i++) pending.add(new ArrayList<>());
        int serial = 0;
        // Same delayed-confirmation pattern as Core's BlockPolicyEstimator tests.
        for (int height = 0; height < 200; height++) {
            for (int level = 0; level < 10; level++) {
                for (int sample = 0; sample < 4; sample++) {
                    byte[] raw = new byte[32]; java.nio.ByteBuffer.wrap(raw).putInt(++serial);
                    var id = new Hash256(raw); pending.get(level).add(id);
                    estimator.added(id, height, (level + 1) * 1000, 1000, true);
                }
            }
            var confirmed = new ArrayList<Hash256>();
            for (int level = 9; level >= 9 - height % 10; level--) {
                confirmed.addAll(pending.get(level)); pending.get(level).clear();
            }
            estimator.connected(height + 1, confirmed);
        }
        Long previous = null;
        for (int target = 2; target <= 10; target++) {
            var estimate = estimator.estimate(target, false);
            assertNotNull(estimate.satoshisPerKvB());
            assertTrue(estimate.satoshisPerKvB() >= 1000 && estimate.satoshisPerKvB() <= 10_000);
            if (previous != null) assertTrue(estimate.satoshisPerKvB() <= previous);
            previous = estimate.satoshisPerKvB();
        }
        assertTrue(estimator.estimate(2, true).satoshisPerKvB() >= estimator.estimate(2, false).satoshisPerKvB());
        estimator.shutdown();
        var before = estimator.estimate(6, true);
        var path = directory.resolve("fees/block_policy_estimates.dat"); estimator.save(path);
        var restored = new BlockPolicyFeeEstimator(); assertTrue(restored.load(path));
        assertEquals(before, restored.estimate(6, true));
        Files.write(path, new byte[]{1,2}); assertFalse(restored.load(path));
        assertEquals(before, restored.estimate(6, true));
    }

    @Test void unconfirmedAndRemovedSamplesCannotPretendToBeConfirmations() {
        var estimator = new BlockPolicyFeeEstimator();
        estimator.connected(100, List.of());
        for (int i = 0; i < 50; i++) {
            byte[] raw = new byte[32]; raw[0] = (byte) i;
            estimator.added(new Hash256(raw), 100, 1000, 100, true);
        }
        for (int height = 101; height < 120; height++) estimator.connected(height, List.of());
        estimator.shutdown();
        assertNull(estimator.estimate(2, true).satoshisPerKvB());
    }
}

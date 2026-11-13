package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class EvictionChunkWeightTest {
    @Test void negativeModifiedRateRoundsDownAndIsNotClampedBeforeIncrement() {
        var transaction = tx(List.of(FUND), 2, 100000);
        var entries = new LinkedHashMap<ru.bitcoin.node.common.types.Hash256, MempoolEntry>();
        entries.put(transaction.txId(), new MempoolEntry(transaction, 0, 401, 0, 0, -1));
        long removed = MempoolGraphPolicy.trim(entries, 0, null);
        assertEquals(-10, removed); // floor(-1000 / 101)
        var rolling = new RollingMinimumFee();
        rolling.bump(removed, 1000, 0);
        assertEquals(990, rolling.get(0, 0, 1000, 1000));
    }

    @Test void wideFeeMultiplicationMayOverflowLongWhileFinalRateFits() {
        var transaction = tx(List.of(FUND), 2, 100000);
        long delta = Long.MAX_VALUE / 1000 + 1;
        var entries = new LinkedHashMap<ru.bitcoin.node.common.types.Hash256, MempoolEntry>();
        entries.put(transaction.txId(), new MempoolEntry(transaction, 0, 4000, 0, 0, delta));
        assertEquals(delta, MempoolGraphPolicy.trim(entries, 0, null));
    }

    @Test void evictingZeroFeeEntryStillRaisesRollingFloorByIncrementalRelayFee() {
        var zero = tx(List.of(FUND), 2, 100000);
        long size = 1100; // Fits one entry plus the retained two-slot randomized array.
        var pool = new Mempool(new MempoolPolicy(),
                new MempoolLimits(64, 101000, size, 1000, 1000), java.time.Clock.systemUTC());
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(zero));
        assertTrue(pool.contains(zero.txId()));
        assertEquals(0, pool.minimumFeeRate(), "No eviction must not bump the rolling floor");
        var otherFund = new ru.bitcoin.node.protocol.transaction.OutPoint(
                ru.bitcoin.node.common.types.Hash256.fromDisplayHex("22".repeat(32)), new ru.bitcoin.node.common.types.UInt32(0));
        var highFee = tx(List.of(otherFund), 2, 90000);
        pool.admit(highFee, CONTEXT, point -> point.equals(otherFund)
                ? Optional.of(new ru.bitcoin.node.consensus.transaction.UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(point));
        assertFalse(pool.contains(zero.txId()));
        assertTrue(pool.contains(highFee.txId()));
        assertEquals(1000, pool.minimumFeeRate());
    }

    @Test void removedChunkRoundsAggregateAdjustedWeightOnce() {
        for (long[] weights : List.of(new long[]{401, 401}, new long[]{401, 402},
                new long[]{403, 402}, new long[]{400, 404})) {
            var parent = tx(List.of(FUND), 2, 100000);
            var child = tx(List.of(point(parent, 0)), 2, 99799);
            var entries = new LinkedHashMap<ru.bitcoin.node.common.types.Hash256, MempoolEntry>();
            entries.put(parent.txId(), new MempoolEntry(parent, 0, weights[0], 0));
            entries.put(child.txId(), new MempoolEntry(child, 201, weights[1], 0));
            // Core ToFeePerVSize({201, total_weight}), then GetFeePerK().
            long expected = weights[0] + weights[1] <= 804 ? 1000 : 995;
            assertEquals(expected, MempoolGraphPolicy.trim(entries, 0, null), Arrays.toString(weights));
            assertTrue(entries.isEmpty());
        }
    }

    @Test void removedChunkUsesSigopsAdjustedWeightAndModifiedFee() {
        var parent = tx(List.of(FUND), 2, 100000);
        var child = tx(List.of(point(parent, 0)), 2, 99999);
        var entries = new LinkedHashMap<ru.bitcoin.node.common.types.Hash256, MempoolEntry>();
        // The child's 20*24 sigop weight dominates its 401 serialized weight.
        entries.put(parent.txId(), new MempoolEntry(parent, 0, 401, 0));
        entries.put(child.txId(), new MempoolEntry(child, 1, 401, 0, 24, 220));
        assertEquals(1000, MempoolGraphPolicy.trim(entries, 0, null)); // 221 / ceil(881/4)
        assertTrue(entries.isEmpty());
    }
}

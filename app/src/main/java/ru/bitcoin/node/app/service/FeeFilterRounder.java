package ru.bitcoin.node.app.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Bitcoin Core-style BIP133 fee-filter privacy quantizer.
 *
 * Core v31.1 builds geometrically spaced buckets from half the incremental
 * relay fee (at least 1 sat/kvB), with spacing 1.1, up to 10,000,000 sat/kvB.
 * round() chooses lower_bound(current) and steps down with probability 2/3.
 */
final class FeeFilterRounder {

    static final long MAX_FILTER_FEERATE = 10_000_000L;
    static final double FEE_FILTER_SPACING = 1.1d;

    private final long[] buckets;
    private final RandomGenerator random;

    FeeFilterRounder(long incrementalRelaySatPerKvB, RandomGenerator random) {
        if (incrementalRelaySatPerKvB < 0) {
            throw new IllegalArgumentException("incremental relay fee must not be negative");
        }
        this.random = Objects.requireNonNull(random, "random");

        List<Long> values = new ArrayList<>();
        values.add(0L);

        double boundary = Math.max(1L, incrementalRelaySatPerKvB / 2L);
        long previous = -1L;
        while (boundary <= MAX_FILTER_FEERATE) {
            long value = (long) boundary;
            if (value > previous) {
                values.add(value);
                previous = value;
            }
            boundary *= FEE_FILTER_SPACING;
        }

        buckets = values.stream().mapToLong(Long::longValue).toArray();
    }

    synchronized long round(long currentMinimumFee) {
        if (currentMinimumFee < 0) {
            throw new IllegalArgumentException("current minimum fee must not be negative");
        }

        int index = lowerBound(currentMinimumFee);

        if (index == buckets.length) {
            index--;
        } else if (index > 0 && random.nextInt(3) != 0) {
            index--;
        }

        return buckets[index];
    }

    private int lowerBound(long value) {
        int low = 0;
        int high = buckets.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (buckets[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    long maximumRoundedFilter() {
        return buckets[buckets.length - 1];
    }
}

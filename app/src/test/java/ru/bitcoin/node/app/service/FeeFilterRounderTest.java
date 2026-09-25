package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class FeeFilterRounderTest {

    @Test
    void exactBucketCanStayOrStepDown() {
        FeeFilterRounder stay =
                new FeeFilterRounder(1_000, fixedInt(0));
        FeeFilterRounder down =
                new FeeFilterRounder(1_000, fixedInt(1));

        long exact = 500L;

        assertEquals(500L, stay.round(exact));
        assertEquals(0L, down.round(exact));
    }

    @Test
    void valuesAboveMaximumRoundToMaximumBucket() {
        FeeFilterRounder rounder =
                new FeeFilterRounder(1_000, fixedInt(0));

        long rounded = rounder.round(Long.MAX_VALUE);

        assertTrue(rounded > 0);
        assertTrue(rounded <= FeeFilterRounder.MAX_FILTER_FEERATE);
        assertEquals(rounder.maximumRoundedFilter(), rounded);
    }

    @Test
    void rejectsNegativeInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FeeFilterRounder(-1, fixedInt(0))
        );

        FeeFilterRounder rounder =
                new FeeFilterRounder(1_000, fixedInt(0));

        assertThrows(
                IllegalArgumentException.class,
                () -> rounder.round(-1)
        );
    }

    private static RandomGenerator fixedInt(int value) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0L; }
            @Override public int nextInt(int bound) { return Math.floorMod(value, bound); }
        };
    }
}

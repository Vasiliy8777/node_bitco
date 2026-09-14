package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeeRateTest {

    @Test
    void mustStoreSatoshisPerKilobyte() {

        FeeRate rate =
                new FeeRate(
                        1_000L
                );

        assertEquals(
                1_000L,
                rate.satoshisPerKiloByte()
        );

        assertEquals(
                1.0,
                rate.satoshisPerVByte()
        );
    }

    @Test
    void mustCalculateRateFromFeeAndVSize() {

        FeeRate rate =
                FeeRate.fromFeeAndVSize(
                        250L,
                        100L
                );

        assertEquals(
                2_500L,
                rate.satoshisPerKiloByte()
        );
    }

    @Test
    void rateConstructionMustRoundDown() {

        /*
         * 100 sat / 73 vB
         *
         * 100 * 1000 / 73
         * = 1369 remainder...
         */
        FeeRate rate =
                FeeRate.fromFeeAndVSize(
                        100L,
                        73L
                );

        assertEquals(
                1_369L,
                rate.satoshisPerKiloByte()
        );
    }

    @Test
    void feeForVSizeMustRoundUp() {

        FeeRate rate =
                new FeeRate(
                        1_001L
                );

        /*
         * 1001 * 100 / 1000
         * = 100.1
         *
         * minimum fee = 101 sat
         */
        assertEquals(
                101L,
                rate.feeForVSize(
                        100L
                )
        );
    }

    @Test
    void oneSatPerVByteMustCalculateExpectedFee() {

        FeeRate rate =
                new FeeRate(
                        1_000L
                );

        assertEquals(
                141L,
                rate.feeForVSize(
                        141L
                )
        );
    }

    @Test
    void feeForWeightMustUseVirtualSizeRoundedUp() {

        FeeRate rate =
                new FeeRate(
                        1_000L
                );

        /*
         * 401 WU -> ceil(401 / 4) = 101 vB
         */
        assertEquals(
                101L,
                rate.feeForWeight(
                        401L
                )
        );
    }

    @Test
    void compareToMustCompareRates() {

        FeeRate low =
                new FeeRate(
                        1_000L
                );

        FeeRate high =
                new FeeRate(
                        2_000L
                );

        assertTrue(
                low.compareTo(high) < 0
        );

        assertTrue(
                high.compareTo(low) > 0
        );

        assertEquals(
                0,
                low.compareTo(
                        new FeeRate(
                                1_000L
                        )
                )
        );
    }

    @Test
    void negativeRateMustBeRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FeeRate(
                                -1L
                        )
        );
    }

    @Test
    void zeroOrNegativeVSizeMustBeRejectedWhenConstructingRate() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeeRate.fromFeeAndVSize(
                                100L,
                                0L
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeeRate.fromFeeAndVSize(
                                100L,
                                -1L
                        )
        );
    }

    @Test
    void negativeFeeMustBeRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeeRate.fromFeeAndVSize(
                                -1L,
                                100L
                        )
        );
    }
}
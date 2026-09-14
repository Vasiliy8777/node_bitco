package ru.bitcoin.node.mempool.policy;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.mempool.FeeRate;
import ru.bitcoin.node.mempool.MempoolAdmissionException;
import ru.bitcoin.node.mempool.MempoolPolicy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MempoolPolicyTest {

    @Test
    void defaultMinRelayFeeMustMatchCurrentBitcoinCoreDefault() {

        MempoolPolicy policy =
                new MempoolPolicy();

        assertEquals(
                100L,
                policy.minRelayFeeRate()
                        .satoshisPerKiloByte()
        );
    }

    @Test
    void feeExactlyAtMinimumMustPass() {

        /*
         * 400 WU -> 100 vB
         *
         * 1000 sat/kvB -> 100 sat required.
         */
        MempoolPolicy policy =
                new MempoolPolicy(
                        new FeeRate(1_000L)
                );

        assertDoesNotThrow(
                () ->
                        policy.validateFee(
                                100L,
                                400L
                        )
        );
    }

    @Test
    void feeBelowMinimumMustFail() {

        MempoolPolicy policy =
                new MempoolPolicy(
                        new FeeRate(1_000L)
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateFee(
                                99L,
                                400L
                        )
        );
    }

    @Test
    void requiredFeeMustUseRoundedUpVirtualSize() {

        /*
         * 401 WU -> 101 vB
         *
         * 1000 sat/kvB -> 101 sat required.
         */
        MempoolPolicy policy =
                new MempoolPolicy(
                        new FeeRate(1_000L)
                );

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateFee(
                                100L,
                                401L
                        )
        );

        assertDoesNotThrow(
                () ->
                        policy.validateFee(
                                101L,
                                401L
                        )
        );
    }

    @Test
    void currentDefaultRateMustRoundRequiredFeeUp() {

        /*
         * Current default:
         * 100 sat/kvB = 0.1 sat/vB
         *
         * 101 vB -> 10.1 sat -> 11 sat.
         */
        MempoolPolicy policy =
                new MempoolPolicy();

        assertThrows(
                MempoolAdmissionException.class,
                () ->
                        policy.validateFee(
                                10L,
                                401L
                        )
        );

        assertDoesNotThrow(
                () ->
                        policy.validateFee(
                                11L,
                                401L
                        )
        );
    }

    @Test
    void invalidArgumentsMustBeRejected() {

        MempoolPolicy policy =
                new MempoolPolicy();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateFee(
                                -1L,
                                400L
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateFee(
                                1L,
                                0L
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.validateFee(
                                1L,
                                -1L
                        )
        );
    }
}
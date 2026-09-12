package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LockTimeCutoffTest {

    @Test
    void shouldUseBlockTimestampBeforeMainnetBip113Activation() {

        long cutoff =
                LockTimeCutoff.calculate(
                        419_327L,
                        1_500_000_000L,
                        1_499_999_000L,
                        NetworkParametersRegistry.mainnet()
                );

        assertEquals(
                1_500_000_000L,
                cutoff
        );
    }

    @Test
    void shouldUsePreviousMedianTimePastAtMainnetActivation() {

        long cutoff =
                LockTimeCutoff.calculate(
                        419_328L,
                        1_500_000_000L,
                        1_499_999_000L,
                        NetworkParametersRegistry.mainnet()
                );

        assertEquals(
                1_499_999_000L,
                cutoff
        );
    }

    @Test
    void shouldUsePreviousMedianTimePastAfterMainnetActivation() {

        long cutoff =
                LockTimeCutoff.calculate(
                        500_000L,
                        1_600_000_000L,
                        1_599_999_000L,
                        NetworkParametersRegistry.mainnet()
                );

        assertEquals(
                1_599_999_000L,
                cutoff
        );
    }

    @Test
    void shouldUsePreviousMedianTimePastFromRegtestHeightOne() {

        long cutoff =
                LockTimeCutoff.calculate(
                        1L,
                        1_700_000_100L,
                        1_700_000_000L,
                        NetworkParametersRegistry.regtest()
                );

        assertEquals(
                1_700_000_000L,
                cutoff
        );
    }
}
package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NextWorkRequiredTest {

    @Test
    void shouldKeepPreviousBitsBetweenAdjustmentIntervals() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        UInt32 previousBits =
                new UInt32(0x1D00FFFFL);

        UInt32 result =
                NextWorkRequired.calculate(
                        1000,
                        previousBits,
                        0,
                        0,
                        parameters
                );

        assertEquals(
                previousBits,
                result
        );
    }

    @Test
    void shouldRecalculateBitsAtAdjustmentBoundary() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        UInt32 previousBits =
                new UInt32(0x1D00FFFFL);

        long targetTimespan =
                parameters.targetTimespanSeconds();

        UInt32 result =
                NextWorkRequired.calculate(
                        2016,
                        previousBits,
                        0,
                        targetTimespan / 2,
                        parameters
                );

        long expectedBits =
                DifficultyAdjustment.calculateNextBits(
                        previousBits.value(),
                        targetTimespan / 2,
                        parameters
                );

        assertEquals(
                new UInt32(expectedBits),
                result
        );
    }

    @Test
    void regtestShouldKeepPreviousBits() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        UInt32 previousBits =
                new UInt32(0x207FFFFFL);

        UInt32 result =
                NextWorkRequired.calculate(
                        2016,
                        previousBits,
                        0,
                        1,
                        parameters
                );

        assertEquals(
                previousBits,
                result
        );
    }
}
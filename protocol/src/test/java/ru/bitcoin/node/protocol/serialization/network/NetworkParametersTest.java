package ru.bitcoin.node.protocol.serialization.network;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.*;

class NetworkParametersTest {

    @Test
    void mainnetShouldUse2016BlockAdjustmentInterval() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        assertEquals(
                600,
                parameters.targetSpacingSeconds()
        );

        assertEquals(
                1_209_600,
                parameters.targetTimespanSeconds()
        );

        assertEquals(
                2016,
                parameters.difficultyAdjustmentInterval()
        );
    }

    @Test
    void regtestShouldDisableRetargeting() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        assertTrue(
                parameters.noRetargeting()
        );
    }

    @Test
    void mainnetShouldNotAllowMinimumDifficultyBlocks() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        assertFalse(
                parameters.allowMinDifficultyBlocks()
        );
    }
}

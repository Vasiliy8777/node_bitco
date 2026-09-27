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
    @Test
    void exposesCurrentTrustedAssumeUtxoAnchors() {
        var main = NetworkParametersRegistry.mainnet();
        assertTrue(main.assumeUtxoData().stream().anyMatch(a -> a.height() == 965_000L
                && a.blockHash().toDisplayHex().equals("00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9")));
        var regtest = NetworkParametersRegistry.regtest();
        assertTrue(regtest.assumeUtxoData().stream().anyMatch(a -> a.height() == 299L
                && a.chainTxCount() == 334L));
    }

}

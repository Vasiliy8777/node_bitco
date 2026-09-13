package ru.bitcoin.node.protocol.serialization.network;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NetworkParametersRegistryTest {
    @Test
    void shouldExposeCorrectBip34ActivationHeights() {
        assertEquals(
                227_931L,
                NetworkParametersRegistry.mainnet().bip34Height()
        );

        assertEquals(
                21_111L,
                NetworkParametersRegistry.testnet().bip34Height()
        );

        assertEquals(
                1L,
                NetworkParametersRegistry.signet().bip34Height()
        );

        assertEquals(
                1L,
                NetworkParametersRegistry.regtest().bip34Height()
        );
    }
    @Test
    void shouldExposeCorrectCsvActivationHeights() {

        assertEquals(
                419_328L,
                NetworkParametersRegistry.mainnet()
                        .csvHeight()
        );

        assertEquals(
                770_112L,
                NetworkParametersRegistry.testnet()
                        .csvHeight()
        );

        assertEquals(
                1L,
                NetworkParametersRegistry.signet()
                        .csvHeight()
        );

        assertEquals(
                1L,
                NetworkParametersRegistry.regtest()
                        .csvHeight()
        );
    }
    @Test
    void shouldExposeCorrectTestnetActivationHeights() {

        NetworkParameters parameters =
                NetworkParametersRegistry.testnet();

        assertEquals(
                21_111L,
                parameters.bip34Height()
        );

        assertEquals(
                330_776L,
                parameters.bip66Height()
        );

        assertEquals(
                581_885L,
                parameters.bip65Height()
        );

        assertEquals(
                770_112L,
                parameters.csvHeight()
        );
    }

}

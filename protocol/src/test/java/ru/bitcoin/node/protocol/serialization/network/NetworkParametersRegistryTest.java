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


    @Test
    void shouldExposeBitcoinCore31_1MinimumChainWorkAndAssumeValid() {
        assertEquals(
                new java.math.BigInteger("0000000000000000000000000000000000000001128750f82f4c366153a3a030", 16),
                NetworkParametersRegistry.mainnet().minimumChainWork()
        );
        assertEquals(
                "00000000000000000000ccebd6d74d9194d8dcdc1d177c478e094bfad51ba5ac",
                NetworkParametersRegistry.mainnet().defaultAssumeValid().toDisplayHex()
        );
        assertEquals(
                new java.math.BigInteger("0000000000000000000000000000000000000000000017dde1c649f3708d14b6", 16),
                NetworkParametersRegistry.testnet().minimumChainWork()
        );
        assertEquals(
                new java.math.BigInteger("00000000000000000000000000000000000000000000000000000b463ea0a4b8", 16),
                NetworkParametersRegistry.signet().minimumChainWork()
        );
        assertEquals(java.math.BigInteger.ZERO, NetworkParametersRegistry.regtest().minimumChainWork());
    }
}

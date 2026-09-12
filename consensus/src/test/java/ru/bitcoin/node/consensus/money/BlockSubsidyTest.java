package ru.bitcoin.node.consensus.money;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BlockSubsidyTest {

    @Test
    void shouldCalculateMainnetInitialSubsidy() {

        NetworkParameters parameters = NetworkParametersRegistry.mainnet();


        assertEquals(
                50L * Money.SATOSHIS_PER_BTC,
                BlockSubsidy.calculate(
                        0,
                        parameters
                )
        );
    }

    @Test
    void shouldHalveMainnetSubsidyAtBlock210000() {

        NetworkParameters parameters = NetworkParametersRegistry.mainnet();

        assertEquals(
                25L * Money.SATOSHIS_PER_BTC,
                BlockSubsidy.calculate(
                        210_000,
                        parameters
                )
        );
    }

    @Test
    void shouldNotHalveBeforeMainnetBoundary() {

        NetworkParameters parameters = NetworkParametersRegistry.mainnet();

        assertEquals(
                50L * Money.SATOSHIS_PER_BTC,
                BlockSubsidy.calculate(
                        209_999,
                        parameters
                )
        );
    }

    @Test
    void shouldUseRegtestHalvingInterval() {

        NetworkParameters parameters = NetworkParametersRegistry.regtest();

        assertEquals(
                50L * Money.SATOSHIS_PER_BTC,
                BlockSubsidy.calculate(
                        149,
                        parameters
                )
        );

        assertEquals(
                25L * Money.SATOSHIS_PER_BTC,
                BlockSubsidy.calculate(
                        150,
                        parameters
                )
        );
    }

    @Test
    void shouldReturnZeroAfter64Halvings() {

        NetworkParameters parameters = NetworkParametersRegistry.mainnet();

        long height =
                parameters.subsidyHalvingInterval()
                        * 64L;

        assertEquals(
                0L,
                BlockSubsidy.calculate(
                        height,
                        parameters
                )
        );
    }
}

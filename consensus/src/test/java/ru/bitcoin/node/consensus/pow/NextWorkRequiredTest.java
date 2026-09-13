package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NextWorkRequiredTest {
    private static NetworkParameters parameters(
            boolean enforceBip94
    ) {
        return new NetworkParameters(
                NetworkParametersRegistry.regtest()
                        .network(),
                1L,
                18444,
                NetworkParametersRegistry.regtest()
                        .genesisBlockHash(),
                NetworkParametersRegistry.regtest().bip16ExceptionBlockHash(),
                new BigInteger(
                        "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        16
                ),
                600L,
                1200L,
                NetworkParametersRegistry.regtest()
                        .subsidyHalvingInterval(),
                NetworkParametersRegistry.regtest().bip34Height(),
                NetworkParametersRegistry.regtest().bip66Height(),
                NetworkParametersRegistry.regtest().bip65Height(),
                NetworkParametersRegistry.regtest().csvHeight(),
                NetworkParametersRegistry.regtest().segwitHeight(),
                true,
                enforceBip94,
                false
        );
    }

    @Test
    void shouldUsePreviousBlockBitsWithoutBip94() {

        NetworkParameters parameters =
                parameters(false);

        UInt32 firstBits =
                new UInt32(
                        0x1d00ffffL
                );

        UInt32 previousBits =
                new UInt32(
                        0x1c0fffffL
                );

        UInt32 result =
                NextWorkRequired.calculate(
                        2,
                        previousBits,
                        firstBits,
                        1_000L,
                        2_200L,
                        parameters
                );

        UInt32 expected =
                new UInt32(
                        DifficultyAdjustment
                                .calculateNextBits(
                                        previousBits.value(),
                                        1_200L,
                                        parameters
                                )
                );

        assertEquals(
                expected,
                result
        );
    }

    @Test
    void shouldUseFirstBlockBitsForBip94Retarget() {

        NetworkParameters parameters =
                parameters(true);

        UInt32 firstBits =
                new UInt32(
                        0x1d00ffffL
                );

        UInt32 previousBits =
                new UInt32(
                        0x1c0fffffL
                );

        UInt32 result =
                NextWorkRequired.calculate(
                        2,
                        previousBits,
                        firstBits,
                        1_000L,
                        2_200L,
                        parameters
                );

        UInt32 expected =
                new UInt32(
                        DifficultyAdjustment
                                .calculateNextBits(
                                        firstBits.value(),
                                        1_200L,
                                        parameters
                                )
                );

        assertEquals(
                expected,
                result
        );
    }

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
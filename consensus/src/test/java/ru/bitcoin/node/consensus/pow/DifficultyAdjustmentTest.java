package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DifficultyAdjustmentTest {

    @Test
    void targetShouldStaySameWhenTimespanMatchesExpected() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        BigInteger previousTarget =
                CompactTarget.decode(
                        0x1D00FFFFL
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        parameters.targetTimespanSeconds(),
                        parameters
                );

        assertEquals(
                previousTarget,
                result
        );
    }

    @Test
    void targetShouldBecomeHarderWhenBlocksWereTooFast() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        BigInteger previousTarget =
                CompactTarget.decode(
                        0x1D00FFFFL
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        parameters.targetTimespanSeconds() / 2,
                        parameters
                );

        assertEquals(
                previousTarget.divide(BigInteger.TWO),
                result
        );
    }

    @Test
    void targetShouldBecomeEasierWhenBlocksWereTooSlow() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        BigInteger previousTarget =
                CompactTarget.decode(
                        0x1C0FFFF0L
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        parameters.targetTimespanSeconds() * 2,
                        parameters
                );

        assertEquals(
                previousTarget.multiply(BigInteger.TWO),
                result
        );
    }

    @Test
    void adjustmentShouldBeLimitedToFourTimesEasier() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        BigInteger previousTarget =
                CompactTarget.decode(
                        0x1C0FFFF0L
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        parameters.targetTimespanSeconds() * 100,
                        parameters
                );

        assertEquals(
                previousTarget.multiply(
                        BigInteger.valueOf(4)
                ),
                result
        );
    }

    @Test
    void adjustmentShouldBeLimitedToFourTimesHarder() {

        NetworkParameters parameters =
                NetworkParametersRegistry.mainnet();

        BigInteger previousTarget =
                CompactTarget.decode(
                        0x1C0FFFF0L
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        1,
                        parameters
                );

        assertEquals(
                previousTarget.divide(
                        BigInteger.valueOf(4)
                ),
                result
        );
    }

    @Test
    void regtestShouldNotRetarget() {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        BigInteger previousTarget =
                new BigInteger(
                        "123456789abcdef",
                        16
                );

        BigInteger result =
                DifficultyAdjustment.calculateNextTarget(
                        previousTarget,
                        123,
                        parameters
                );

        assertEquals(
                previousTarget,
                result
        );
    }
}
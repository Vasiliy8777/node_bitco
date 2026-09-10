package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChainWorkTest {

    @Test
    void shouldCalculatePositiveGenesisBlockWork() {

        BigInteger work =
                ChainWork.blockWork(
                        0x1D00FFFFL
                );

        assertTrue(
                work.signum() > 0
        );
    }

    @Test
    void harderTargetShouldProduceMoreWork() {

        BigInteger easyTarget =
                new BigInteger(
                        "00000000ffff0000000000000000000000000000000000000000000000000000",
                        16
                );

        BigInteger harderTarget =
                easyTarget.shiftRight(1);

        BigInteger easyWork =
                ChainWork.blockWork(
                        easyTarget
                );

        BigInteger hardWork =
                ChainWork.blockWork(
                        harderTarget
                );

        assertTrue(
                hardWork.compareTo(easyWork) > 0
        );
    }
    @Test
    void shouldAddBlockWorkToChainWork() {

        BigInteger chainWork =
                BigInteger.valueOf(1000);

        BigInteger blockWork =
                BigInteger.valueOf(250);

        BigInteger result =
                ChainWork.add(
                        chainWork,
                        blockWork
                );

        assertEquals(
                BigInteger.valueOf(1250),
                result
        );
    }

    @Test
    void shouldRejectZeroTarget() {

        assertThrows(
                IllegalArgumentException.class,
                () -> ChainWork.blockWork(
                        BigInteger.ZERO
                )
        );
    }

    @Test
    void shouldRejectTargetOutsideUint256Range() {

        BigInteger invalidTarget =
                BigInteger.ONE.shiftLeft(256);

        assertThrows(
                IllegalArgumentException.class,
                () -> ChainWork.blockWork(
                        invalidTarget
                )
        );
    }
}

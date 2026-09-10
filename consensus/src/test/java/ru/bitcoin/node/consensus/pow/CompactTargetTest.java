package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactTargetTest {

    @Test
    void shouldDecodeGenesisTarget() {

        BigInteger target =
                CompactTarget.decode(
                        0x1D00FFFFL
                );

        assertEquals(
                new BigInteger(
                        "00000000ffff0000000000000000000000000000000000000000000000000000",
                        16
                ),
                target
        );
    }
    @Test
    void genesisTargetShouldEncodeBackToBits() {

        BigInteger target =
                new BigInteger(
                        "00000000ffff0000000000000000000000000000000000000000000000000000",
                        16
                );

        long bits =
                CompactTarget.encode(target);

        assertEquals(
                0x1D00FFFFL,
                bits
        );
    }
}

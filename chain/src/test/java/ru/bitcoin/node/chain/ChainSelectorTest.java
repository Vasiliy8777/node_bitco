package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertSame;

class ChainSelectorTest {

    @Test
    void shouldSelectChainWithMoreWork() {

        BlockIndex weaker =
                index(
                        10,
                        BigInteger.valueOf(1000),
                        1
                );

        BlockIndex stronger =
                index(
                        9,
                        BigInteger.valueOf(2000),
                        2
                );

        BlockIndex result =
                ChainSelector.selectBest(
                        weaker,
                        stronger
                );

        assertSame(
                stronger,
                result
        );
    }

    @Test
    void heightShouldNotOverrideChainWork() {

        BlockIndex taller =
                index(
                        100,
                        BigInteger.valueOf(1000),
                        1
                );

        BlockIndex shorterButStronger =
                index(
                        90,
                        BigInteger.valueOf(2000),
                        2
                );

        BlockIndex result =
                ChainSelector.selectBest(
                        taller,
                        shorterButStronger
                );

        assertSame(
                shorterButStronger,
                result
        );
    }

    @Test
    void shouldKeepFirstWhenChainWorkIsEqual() {

        BlockIndex first =
                index(
                        10,
                        BigInteger.valueOf(1000),
                        1
                );

        BlockIndex second =
                index(
                        10,
                        BigInteger.valueOf(1000),
                        2
                );

        BlockIndex result =
                ChainSelector.selectBest(
                        first,
                        second
                );

        assertSame(
                first,
                result
        );
    }

    private static BlockIndex index(
            long height,
            BigInteger chainWork,
            long nonce
    ) {

        BlockHeader header =
                new BlockHeader(
                        1,
                        Hash256.fromDisplayHex(
                                "0000000000000000000000000000000000000000000000000000000000000000"
                        ),
                        Hash256.fromDisplayHex(
                                "0000000000000000000000000000000000000000000000000000000000000001"
                        ),
                        new UInt32(1231006505L),
                        new UInt32(0x1D00FFFFL),
                        new UInt32(nonce)
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                header.previousBlockHash(),
                chainWork
        );
    }
}
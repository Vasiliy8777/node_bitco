package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderChainStateTest {

    @Test
    void shouldReplaceBestHeaderTipWhenCandidateHasMoreChainWork() {

        BlockIndex current =
                blockIndex(
                        1,
                        BigInteger.valueOf(100)
                );

        BlockIndex candidate =
                blockIndex(
                        2,
                        BigInteger.valueOf(101)
                );

        HeaderChainState state =
                new HeaderChainState(
                        current
                );

        boolean changed =
                state.consider(
                        candidate
                );

        assertTrue(
                changed
        );

        assertEquals(
                candidate,
                state.bestHeaderTip()
        );
    }

    @Test
    void shouldKeepBestHeaderTipWhenCandidateHasLessChainWork() {

        BlockIndex current =
                blockIndex(
                        1,
                        BigInteger.valueOf(100)
                );

        /*
         * Higher height intentionally has less work.
         *
         * Height must not determine the best header chain.
         */
        BlockIndex candidate =
                blockIndex(
                        100,
                        BigInteger.valueOf(99)
                );

        HeaderChainState state =
                new HeaderChainState(
                        current
                );

        boolean changed =
                state.consider(
                        candidate
                );

        assertFalse(
                changed
        );

        assertEquals(
                current,
                state.bestHeaderTip()
        );
    }

    @Test
    void shouldKeepBestHeaderTipWhenCandidateHasEqualChainWork() {

        BlockIndex current =
                blockIndex(
                        1,
                        BigInteger.valueOf(100)
                );

        BlockIndex candidate =
                blockIndex(
                        2,
                        BigInteger.valueOf(100)
                );

        HeaderChainState state =
                new HeaderChainState(
                        current
                );

        boolean changed =
                state.consider(
                        candidate
                );

        assertFalse(
                changed
        );

        assertEquals(
                current,
                state.bestHeaderTip()
        );
    }

    private static BlockIndex blockIndex(
            long height,
            BigInteger chainWork
    ) {

        Hash256 previousBlockHash =
                hash(
                        height
                );

        BlockHeader header =
                new BlockHeader(
                        4,
                        previousBlockHash,
                        hash(
                                height + 1
                        ),
                        new UInt32(
                                1_700_000_000L + height
                        ),
                        new UInt32(
                                0x207fffffL
                        ),
                        new UInt32(
                                height
                        )
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousBlockHash,
                chainWork
        );
    }

    private static Hash256 hash(
            long value
    ) {

        return Hash256.fromDisplayHex(
                "%064x".formatted(
                        value
                )
        );
    }
}
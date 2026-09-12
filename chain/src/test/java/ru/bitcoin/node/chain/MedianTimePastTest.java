package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MedianTimePastTest {

    @Test
    void shouldCalculateMedianOfLastElevenBlocks() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex previous = null;

        /*
         * Специально не по порядку времени,
         * чтобы тест проверял именно median,
         * а не просто шестой блок цепочки.
         */
        long[] timestamps = {
                100,
                500,
                200,
                900,
                300,
                700,
                400,
                1100,
                600,
                1000,
                800
        };

        for (int height = 0;
             height < timestamps.length;
             height++) {

            Hash256 previousHash =
                    previous == null
                            ? zeroHash()
                            : previous.hash();

            BlockHeader header =
                    header(
                            previousHash,
                            timestamps[height],
                            height
                    );

            BlockIndex index =
                    new BlockIndex(
                            header.hash(),
                            header,
                            height,
                            previousHash,
                            BigInteger.valueOf(
                                    height + 1L
                            )
                    );

            indexes.put(
                    index.hash(),
                    index
            );

            previous = index;
        }

        BlockIndex tip = previous;

        BlockIndexLookup lookup =
                indexes::get;

        /*
         * Sorted:
         *
         * 100,200,300,400,500,600,
         * 700,800,900,1000,1100
         *
         * median = 600
         */
        assertEquals(
                600L,
                MedianTimePast.calculate(
                        tip,
                        lookup
                )
        );
    }

    @Test
    void shouldUseAvailableBlocksNearGenesis() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex genesis =
                createIndex(
                        null,
                        0,
                        100
                );

        indexes.put(
                genesis.hash(),
                genesis
        );

        BlockIndex block1 =
                createIndex(
                        genesis,
                        1,
                        300
                );

        indexes.put(
                block1.hash(),
                block1
        );

        BlockIndex block2 =
                createIndex(
                        block1,
                        2,
                        200
                );

        indexes.put(
                block2.hash(),
                block2
        );

        /*
         * Sorted:
         * 100, 200, 300
         *
         * median = 200
         */
        assertEquals(
                200L,
                MedianTimePast.calculate(
                        block2,
                        indexes::get
                )
        );
    }

    private static BlockIndex createIndex(
            BlockIndex parent,
            long height,
            long timestamp
    ) {
        Hash256 previousHash =
                parent == null
                        ? zeroHash()
                        : parent.hash();

        BlockHeader header =
                header(
                        previousHash,
                        timestamp,
                        height
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousHash,
                BigInteger.valueOf(
                        height + 1
                )
        );
    }

    private static BlockHeader header(
            Hash256 previousHash,
            long timestamp,
            long nonce
    ) {
        return new BlockHeader(
                1,
                previousHash,
                Hash256.fromDisplayHex(
                        "11".repeat(32)
                ),
                new UInt32(timestamp),
                new UInt32(
                        0x207fffffL
                ),
                new UInt32(nonce)
        );
    }

    private static Hash256 zeroHash() {
        return Hash256.fromDisplayHex(
                "00".repeat(32)
        );
    }
}
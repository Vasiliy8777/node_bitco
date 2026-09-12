package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;

public final class BlockWeight {

    public static final long MAX_BLOCK_WEIGHT =
            4_000_000L;

    private static final long WITNESS_SCALE_FACTOR =
            4L;

    private BlockWeight() {
    }

    public static long calculate(
            Block block
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        long strippedSize =
                BlockSerializer.serializeLegacy(
                        block
                ).length;

        long totalSize =
                BlockSerializer.serialize(
                        block
                ).length;

        /*
         * Bitcoin weight:
         *
         * weight =
         *     strippedSize * 4
         *     + witnessSize
         *
         * где:
         *
         * witnessSize =
         *     totalSize - strippedSize
         *
         * Эквивалентная форма:
         *
         * weight =
         *     strippedSize * 3
         *     + totalSize
         */
        return strippedSize
                * (WITNESS_SCALE_FACTOR - 1)
                + totalSize;
    }

    public static boolean isWithinLimit(
            Block block
    ) {
        return calculate(block)
                <= MAX_BLOCK_WEIGHT;
    }
}
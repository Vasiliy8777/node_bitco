package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.protocol.block.Block;

public record BlockToConnect(
        Block block,
        long height,
        long lockTimeCutoff,
        long previousMedianTimePast
) {

    public BlockToConnect {

        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (height < 0) {
            throw new IllegalArgumentException(
                    "height must not be negative"
            );
        }

        if (lockTimeCutoff < 0) {
            throw new IllegalArgumentException(
                    "lockTimeCutoff must not be negative"
            );
        }

        if (previousMedianTimePast < 0) {
            throw new IllegalArgumentException(
                    "previousMedianTimePast must not be negative"
            );
        }
    }
}
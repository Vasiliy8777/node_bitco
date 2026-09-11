package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.protocol.block.Block;

public record BlockToConnect(
        Block block,
        long height
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
    }
}
package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.undo.BlockUndoData;

public record BlockToDisconnect(
        Block block,
        BlockUndoData undoData
) {

    public BlockToDisconnect {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }
    }
}
package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

public record BlockConnectChanges(
        UtxoChanges utxoChanges,
        BlockUndoData undoData
) {

    public BlockConnectChanges {
        if (utxoChanges == null) {
            throw new IllegalArgumentException(
                    "utxoChanges must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }
    }
}
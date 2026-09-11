package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

import java.util.LinkedHashMap;
import java.util.Map;

public record BlockReorganizationChanges(
        UtxoChanges utxoChanges,
        Map<Hash256, BlockUndoData> connectedBlockUndo
) {

    public BlockReorganizationChanges {

        if (utxoChanges == null) {
            throw new IllegalArgumentException(
                    "utxoChanges must not be null"
            );
        }

        if (connectedBlockUndo == null) {
            throw new IllegalArgumentException(
                    "connectedBlockUndo must not be null"
            );
        }

        connectedBlockUndo =
                Map.copyOf(
                        new LinkedHashMap<>(
                                connectedBlockUndo
                        )
                );
    }
}
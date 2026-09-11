package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;

public record CreatedUtxo(
        OutPoint outPoint,
        StoredUtxo utxo
) {

    public CreatedUtxo {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }
    }
}
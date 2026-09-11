package ru.bitcoin.node.storage.undo;

import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.List;

public final class TransactionUndo {

    private final List<StoredUtxo> spentOutputs;

    public TransactionUndo(
            List<StoredUtxo> spentOutputs
    ) {
        if (spentOutputs == null) {
            throw new IllegalArgumentException(
                    "spentOutputs must not be null"
            );
        }

        for (StoredUtxo utxo : spentOutputs) {
            if (utxo == null) {
                throw new IllegalArgumentException(
                        "spentOutputs must not contain null"
                );
            }
        }

        this.spentOutputs =
                List.copyOf(spentOutputs);
    }

    public List<StoredUtxo> spentOutputs() {
        return spentOutputs;
    }

    @Override
    public boolean equals(Object o) {

        if (this == o) {
            return true;
        }

        if (!(o instanceof TransactionUndo that)) {
            return false;
        }

        return spentOutputs.equals(
                that.spentOutputs
        );
    }

    @Override
    public int hashCode() {
        return spentOutputs.hashCode();
    }
}
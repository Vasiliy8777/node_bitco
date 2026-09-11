package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.Optional;

public interface UtxoStore {

    void save(
            OutPoint outPoint,
            StoredUtxo utxo
    );

    Optional<StoredUtxo> find(
            OutPoint outPoint
    );

    void delete(
            OutPoint outPoint
    );
}

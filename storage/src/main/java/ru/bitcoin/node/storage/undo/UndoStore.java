package ru.bitcoin.node.storage.undo;

import ru.bitcoin.node.common.types.Hash256;

import java.util.Optional;

public interface UndoStore {

    void save(
            Hash256 blockHash,
            BlockUndoData undoData
    );

    Optional<BlockUndoData> find(
            Hash256 blockHash
    );

    void delete(
            Hash256 blockHash
    );
}
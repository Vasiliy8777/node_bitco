package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;

import java.util.Optional;

public interface BlockIndexStore {

    void save(
            StoredBlockIndex blockIndex
    );

    Optional<StoredBlockIndex> find(
            Hash256 hash
    );

    void delete(
            Hash256 hash
    );
}
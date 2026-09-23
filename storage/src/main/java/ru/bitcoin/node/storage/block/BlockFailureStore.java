package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;

public interface BlockFailureStore {

    boolean isFailed(
            Hash256 hash
    );

    void markFailed(
            Hash256 hash
    );
}
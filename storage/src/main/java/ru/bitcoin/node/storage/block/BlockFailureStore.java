package ru.bitcoin.node.storage.block;

import ru.bitcoin.node.common.types.Hash256;

public interface BlockFailureStore {

    /** Monotonic generation of committed failure changes; -1 disables caching. */
    default long revision() { return -1; }

    boolean isFailed(
            Hash256 hash
    );

    void markFailed(
            Hash256 hash
    );
}

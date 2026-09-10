package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;

public final class StoredBlockIndexLookup
        implements BlockIndexLookup {

    private final BlockIndexStore store;

    public StoredBlockIndexLookup(
            BlockIndexStore store
    ) {
        if (store == null) {
            throw new IllegalArgumentException(
                    "store must not be null"
            );
        }

        this.store = store;
    }

    @Override
    public BlockIndex find(
            Hash256 hash
    ) {
        if (hash == null) {
            throw new IllegalArgumentException(
                    "hash must not be null"
            );
        }

        return store.find(hash)
                .map(
                        BlockIndexStorageMapper::fromStored
                )
                .orElse(null);
    }
}
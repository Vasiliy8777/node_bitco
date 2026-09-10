package ru.bitcoin.node.chain;

import ru.bitcoin.node.storage.block.StoredBlockIndex;

public final class BlockIndexStorageMapper {

    private BlockIndexStorageMapper() {
    }

    public static StoredBlockIndex toStored(
            BlockIndex index
    ) {
        if (index == null) {
            throw new IllegalArgumentException(
                    "index must not be null"
            );
        }

        return new StoredBlockIndex(
                index.hash(),
                index.header(),
                index.height(),
                index.previousBlockHash(),
                index.chainWork()
        );
    }

    public static BlockIndex fromStored(
            StoredBlockIndex stored
    ) {
        if (stored == null) {
            throw new IllegalArgumentException(
                    "stored must not be null"
            );
        }

        return new BlockIndex(
                stored.hash(),
                stored.header(),
                stored.height(),
                stored.previousBlockHash(),
                stored.chainWork()
        );
    }
}
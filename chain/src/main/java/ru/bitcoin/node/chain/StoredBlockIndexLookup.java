package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;

public final class StoredBlockIndexLookup implements BlockIndexAncestorLookup {
    private final BlockIndexStore store;

    public StoredBlockIndexLookup(BlockIndexStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.store = store;
    }

    @Override
    public BlockIndex find(Hash256 hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        return store.find(hash).map(BlockIndexStorageMapper::fromStored).orElse(null);
    }

    @Override
    public BlockIndex ancestor(BlockIndex index, long targetHeight) {
        if (index == null) throw new IllegalArgumentException("index must not be null");
        if (targetHeight < 0 || targetHeight > index.height()) {
            throw new IllegalArgumentException("Invalid ancestor height: " + targetHeight);
        }
        if (!(store instanceof RocksDbBlockIndexStore rocks)) {
            return linearAncestor(index, targetHeight);
        }
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            long heightSkip = RocksDbBlockIndexStore.getSkipHeight(current.height());
            long heightSkipPrev = RocksDbBlockIndexStore.getSkipHeight(current.height() - 1);
            Hash256 skipHash = rocks.findSkipHash(current.hash()).orElse(null);
            boolean useSkip = skipHash != null && (heightSkip == targetHeight
                    || (heightSkip > targetHeight && !(heightSkipPrev < heightSkip - 2
                    && heightSkipPrev >= targetHeight)));
            Hash256 nextHash = useSkip ? skipHash : current.previousBlockHash();
            BlockIndex next = find(nextHash);
            if (next == null) throw new IllegalStateException("Missing ancestor for block "
                    + current.hash().toDisplayHex() + " at height " + current.height());
            long expected = useSkip ? heightSkip : current.height() - 1;
            if (next.height() != expected) throw new IllegalStateException("Invalid ancestor height: expected "
                    + expected + " but found " + next.height());
            current = next;
        }
        return current;
    }

    private BlockIndex linearAncestor(BlockIndex index, long targetHeight) {
        BlockIndex current = index;
        while (current.height() > targetHeight) {
            BlockIndex parent = find(current.previousBlockHash());
            if (parent == null) throw new IllegalStateException("Missing ancestor for block "
                    + current.hash().toDisplayHex() + " at height " + current.height());
            current = parent;
        }
        return current;
    }
}

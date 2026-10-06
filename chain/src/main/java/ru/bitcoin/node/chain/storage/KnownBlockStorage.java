package ru.bitcoin.node.chain.storage;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexStorageMapper;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

public final class KnownBlockStorage {

    private final RocksDbDatabase database;
    private final RocksDbBlockStore blockStore;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore availability;

    public KnownBlockStorage(
            RocksDbDatabase database,
            RocksDbBlockStore blockStore,
            RocksDbBlockIndexStore blockIndexStore
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        if (blockStore == null) {
            throw new IllegalArgumentException(
                    "blockStore must not be null"
            );
        }

        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        this.database = database;
        this.blockStore = blockStore;
        this.blockIndexStore = blockIndexStore;
        this.availability = new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database);
    }

    public boolean hasBody(ru.bitcoin.node.common.types.Hash256 hash) {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        return availability.hasData(hash);
    }

    public void save(
            Block block,
            BlockIndex blockIndex
    ) {
        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        if (!block.hash().equals(
                blockIndex.hash()
        )) {
            throw new IllegalArgumentException(
                    "Block hash does not match BlockIndex hash"
            );
        }

        if (!block.header().equals(
                blockIndex.header()
        )) {
            throw new IllegalArgumentException(
                    "Block header does not match BlockIndex header"
            );
        }

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            save(batch, block, blockIndex);
            database.write(batch);
        }
    }

    /** Adds block body, availability and BlockIndex writes to an existing atomic batch. */
    public void save(
            RocksDbWriteBatch batch,
            Block block,
            BlockIndex blockIndex
    ) {
        save(batch, block, blockIndex, null);
    }

    public boolean usesIndexStore(ru.bitcoin.node.chain.StoredBlockIndexLookup lookup) {
        return lookup != null && lookup.isBackedBy(blockIndexStore);
    }

    /**
     * Persists the body using an immutable index already resolved by the validation
     * lookup. The caller must serialize this operation with all chain/index writers
     * and must supply an index that is already committed in this database.
     * A changed index falls back to the regular secondary-index replacement path.
     */
    public void save(
            RocksDbWriteBatch batch,
            Block block,
            BlockIndex blockIndex,
            BlockIndex committedIndex
    ) {
        if (batch == null) throw new IllegalArgumentException("batch must not be null");
        if (block == null) throw new IllegalArgumentException("block must not be null");
        if (blockIndex == null) throw new IllegalArgumentException("blockIndex must not be null");
        if (!block.hash().equals(blockIndex.hash())) {
            throw new IllegalArgumentException("Block hash does not match BlockIndex hash");
        }
        if (!block.header().equals(blockIndex.header())) {
            throw new IllegalArgumentException("Block header does not match BlockIndex header");
        }
        blockStore.save(batch, block);
        availability.markData(batch, block.hash());
        var storedIndex = BlockIndexStorageMapper.toStored(blockIndex);
        if (committedIndex == null
                || !storedIndex.equals(BlockIndexStorageMapper.toStored(committedIndex))) {
            blockIndexStore.save(batch, storedIndex);
        }
    }
}

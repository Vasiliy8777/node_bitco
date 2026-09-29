package ru.bitcoin.node.chain.storage;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexStorageMapper;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.util.List;

public final class KnownHeaderStorage {

    private final RocksDbDatabase database;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;

    public KnownHeaderStorage(
            RocksDbDatabase database,
            RocksDbBlockIndexStore blockIndexStore,
            RocksDbChainStateStore chainStateStore
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.database = database;
        this.blockIndexStore = blockIndexStore;
        this.chainStateStore = chainStateStore;
    }

    /**
     * Atomically persists one validated HEADERS batch. All indexes in {@code newIndexes}
     * must have been proven absent by the validation lookup.
     */
    public void saveBatch(
            List<BlockIndex> newIndexes,
            BlockIndex bestHeaderTip
    ) {
        if (newIndexes == null) {
            throw new IllegalArgumentException("newIndexes must not be null");
        }
        if (newIndexes.stream().anyMatch(index -> index == null)) {
            throw new IllegalArgumentException("newIndexes must not contain null");
        }

        try (RocksDbWriteBatch batch = new RocksDbWriteBatch()) {
            for (BlockIndex index : newIndexes) {
                blockIndexStore.saveNew(
                        batch,
                        BlockIndexStorageMapper.toStored(index)
                );
            }
            if (bestHeaderTip != null) {
                chainStateStore.saveBestHeaderTipHash(
                        batch,
                        bestHeaderTip.hash()
                );
            }
            if (!newIndexes.isEmpty() || bestHeaderTip != null) {
                database.write(batch, true);
            }
        }
    }

    public void save(
            BlockIndex blockIndex,
            boolean updateBestHeaderTip
    ) {
        save(blockIndex, updateBestHeaderTip, true);
    }

    /**
     * Persists one validated header. Intermediate headers of a received HEADERS
     * message may be committed with {@code durable=false}; the final header is
     * committed synchronously, which flushes the WAL for the complete message.
     */
    public void save(
            BlockIndex blockIndex,
            boolean updateBestHeaderTip,
            boolean durable
    ) {
        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            blockIndexStore.save(
                    batch,
                    BlockIndexStorageMapper.toStored(
                            blockIndex
                    )
            );

            if (updateBestHeaderTip) {
                chainStateStore.saveBestHeaderTipHash(
                        batch,
                        blockIndex.hash()
                );
            }

            database.write(
                    batch,
                    durable
            );
        }
    }
}
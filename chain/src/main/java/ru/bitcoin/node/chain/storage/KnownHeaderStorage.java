package ru.bitcoin.node.chain.storage;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.chain.BlockIndexStorageMapper;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

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

    public void save(
            BlockIndex blockIndex,
            boolean updateBestHeaderTip
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
                    batch
            );
        }
    }
}
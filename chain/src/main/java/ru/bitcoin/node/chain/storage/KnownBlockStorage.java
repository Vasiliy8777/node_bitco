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

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            blockStore.save(
                    batch,
                    block
            );

            blockIndexStore.save(
                    batch,
                    BlockIndexStorageMapper.toStored(
                            blockIndex
                    )
            );

            database.write(batch);
        }
    }
}
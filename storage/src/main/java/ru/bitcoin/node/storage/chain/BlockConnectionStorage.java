package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

public final class BlockConnectionStorage {

    private final RocksDbDatabase database;
    private final RocksDbBlockStore blockStore;
    private final RocksDbUtxoStore utxoStore;
    private final RocksDbUndoStore undoStore;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;



    public BlockConnectionStorage(
            RocksDbDatabase database,
            RocksDbUtxoStore utxoStore,
            RocksDbUndoStore undoStore,
            RocksDbBlockIndexStore blockIndexStore,
            RocksDbBlockStore blockStore,
            RocksDbChainStateStore chainStateStore
    ) {
        if (database == null) {
            throw new IllegalArgumentException(
                    "database must not be null"
            );
        }

        if (utxoStore == null) {
            throw new IllegalArgumentException(
                    "utxoStore must not be null"
            );
        }

        if (undoStore == null) {
            throw new IllegalArgumentException(
                    "undoStore must not be null"
            );
        }

        if (blockIndexStore == null) {
            throw new IllegalArgumentException(
                    "blockIndexStore must not be null"
            );
        }

        if (blockStore == null) {
            throw new IllegalArgumentException(
                    "blockStore must not be null"
            );
        }

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.database = database;
        this.utxoStore = utxoStore;
        this.undoStore = undoStore;
        this.blockIndexStore = blockIndexStore;
        this.blockStore = blockStore;
        this.chainStateStore = chainStateStore;
    }


    public void commitConnectedBlock(
            Block block,
            StoredBlockIndex blockIndex,
            UtxoChanges utxoChanges,
            BlockUndoData undoData
    ) {
        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        if (utxoChanges == null) {
            throw new IllegalArgumentException(
                    "utxoChanges must not be null"
            );
        }

        if (undoData == null) {
            throw new IllegalArgumentException(
                    "undoData must not be null"
            );
        }

        if (block == null) {
            throw new IllegalArgumentException(
                    "block must not be null"
            );
        }

        if (!block.header().hash().equals(
                blockIndex.hash()
        )) {
            throw new IllegalArgumentException(
                    "Block hash does not match BlockIndex hash"
            );
        }

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            /*
             * Сначала добавляем ВСЕ outputs,
             * созданные транзакциями блока.
             *
             * Если output был создан и затем потрачен
             * внутри этого же блока, последующий DELETE
             * удалит его из итогового UTXO-set.
             */
            for (CreatedUtxo created
                    : utxoChanges.createdOutputs()) {

                utxoStore.save(
                        batch,
                        created.outPoint(),
                        created.utxo()
                );
            }

            /*
             * Затем удаляем все потраченные outputs.
             */
            for (OutPoint spent
                    : utxoChanges.spentOutputs()) {

                utxoStore.delete(
                        batch,
                        spent
                );
            }

            /*
             * Undo принадлежит именно этому блоку.
             */
            undoStore.save(
                    batch,
                    blockIndex.hash(),
                    undoData
            );
            /*
             * Сам block body.
             */
            blockStore.save(
                    batch,
                    block
            );

            /*
             * Индекс блока.
             */
            blockIndexStore.save(
                    batch,
                    blockIndex
            );

            /*
             * Active tip изменяется в том же atomic batch.
             */
            chainStateStore.saveActiveTipHash(
                    batch,
                    blockIndex.hash()
            );

            /*
             * Единственная точка изменения persistent state.
             */
            database.write(
                    batch
            );
        }
    }
}
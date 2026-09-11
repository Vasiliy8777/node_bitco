package ru.bitcoin.node.storage.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.UtxoChanges;

public final class BlockDisconnectionStorage {

    private final RocksDbDatabase database;
    private final RocksDbUtxoStore utxoStore;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;

    public BlockDisconnectionStorage(
            RocksDbDatabase database,
            RocksDbUtxoStore utxoStore,
            RocksDbBlockIndexStore blockIndexStore,
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
        this.utxoStore = utxoStore;
        this.blockIndexStore = blockIndexStore;
        this.chainStateStore = chainStateStore;
    }

    public void commitDisconnectedBlock(
            StoredBlockIndex blockIndex,
            UtxoChanges rollbackChanges
    ) {
        if (blockIndex == null) {
            throw new IllegalArgumentException(
                    "blockIndex must not be null"
            );
        }

        if (rollbackChanges == null) {
            throw new IllegalArgumentException(
                    "rollbackChanges must not be null"
            );
        }

        if (blockIndex.height() == 0) {
            throw new IllegalArgumentException(
                    "Genesis block cannot be disconnected"
            );
        }

        Hash256 activeTip =
                chainStateStore
                        .loadActiveTipHash()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Active tip is not initialized"
                                        )
                        );

        if (!activeTip.equals(
                blockIndex.hash()
        )) {
            throw new IllegalStateException(
                    "Cannot disconnect block that is not the active tip"
            );
        }

        Hash256 parentHash =
                blockIndex.previousBlockHash();

        /*
         * Заодно проверяем целостность BlockIndex.
         * Родитель отключаемого блока обязан существовать.
         */
        if (blockIndexStore.find(parentHash)
                .isEmpty()) {

            throw new IllegalStateException(
                    "Parent BlockIndex not found: "
                            + parentHash.toDisplayHex()
            );
        }

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            /*
             * Восстанавливаем UTXO,
             * которые блок ранее потратил.
             */
            for (CreatedUtxo restored
                    : rollbackChanges.createdOutputs()) {

                utxoStore.save(
                        batch,
                        restored.outPoint(),
                        restored.utxo()
                );
            }

            /*
             * Удаляем outputs, созданные
             * отключаемым блоком.
             *
             * DELETE идёт после PUT специально:
             * это корректно для output,
             * созданного и потраченного внутри
             * одного и того же блока.
             */
            for (OutPoint createdByBlock
                    : rollbackChanges.spentOutputs()) {

                utxoStore.delete(
                        batch,
                        createdByBlock
                );
            }

            /*
             * Active tip возвращается на родителя.
             */
            chainStateStore.saveActiveTipHash(
                    batch,
                    parentHash
            );

            /*
             * Block, BlockIndex и Undo здесь
             * НЕ удаляем.
             */
            database.write(
                    batch
            );
        }
    }
}
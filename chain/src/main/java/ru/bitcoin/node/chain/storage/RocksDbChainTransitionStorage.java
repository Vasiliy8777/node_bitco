package ru.bitcoin.node.chain.storage;

import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.block.RocksDbBlockIndexStore;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import ru.bitcoin.node.storage.utxo.UtxoChanges;
import ru.bitcoin.node.storage.chain.RocksDbChainStateStore;

import java.util.Map;

public final class RocksDbChainTransitionStorage {

    private final RocksDbDatabase database;
    private final RocksDbUtxoStore utxoStore;
    private final RocksDbUndoStore undoStore;
    private final RocksDbBlockIndexStore blockIndexStore;
    private final RocksDbChainStateStore chainStateStore;
    private final ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore availability;
    private final ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore validationStatus;

    public RocksDbChainTransitionStorage(
            RocksDbDatabase database,
            RocksDbUtxoStore utxoStore,
            RocksDbUndoStore undoStore,
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

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.database = database;
        this.utxoStore = utxoStore;
        this.undoStore = undoStore;
        this.blockIndexStore = blockIndexStore;
        this.chainStateStore = chainStateStore;
        this.availability = new ru.bitcoin.node.storage.block.RocksDbBlockAvailabilityStore(database);
        this.validationStatus = new ru.bitcoin.node.storage.block.RocksDbBlockValidationStatusStore(database);
    }

    public void commit(
            Hash256 expectedOldTip,
            Hash256 newTip,
            BlockReorganizationChanges changes
    ) {
        if (expectedOldTip == null) {
            throw new IllegalArgumentException(
                    "expectedOldTip must not be null"
            );
        }

        if (newTip == null) {
            throw new IllegalArgumentException(
                    "newTip must not be null"
            );
        }

        if (changes == null) {
            throw new IllegalArgumentException(
                    "changes must not be null"
            );
        }

        /*
         * Защита от stale transition.
         *
         * Мы строили UTXO transition относительно
         * конкретного состояния active chain.
         */
        Hash256 actualActiveTip =
                chainStateStore
                        .loadActiveTipHash()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Active tip is not initialized"
                                        )
                        );

        if (!actualActiveTip.equals(
                expectedOldTip
        )) {
            throw new IllegalStateException(
                    "Active tip changed while transition was being prepared. "
                            + "Expected: "
                            + expectedOldTip.toDisplayHex()
                            + ", actual: "
                            + actualActiveTip.toDisplayHex()
            );
        }

        /*
         * Новый tip должен уже быть известным блоком.
         *
         * Block body / BlockIndex боковой ветки
         * сохраняются раньше, когда блок принимается
         * нодой. Reorg не должен создавать BlockIndex
         * из воздуха.
         */
        if (blockIndexStore.find(newTip)
                .isEmpty()) {

            throw new IllegalStateException(
                    "New active tip BlockIndex not found: "
                            + newTip.toDisplayHex()
            );
        }

        UtxoChanges utxoChanges =
                changes.utxoChanges();

        try (RocksDbWriteBatch batch =
                     new RocksDbWriteBatch()) {

            /*
             * Итоговые PUT UTXO.
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
             * Итоговые DELETE UTXO.
             */
            for (OutPoint spent
                    : utxoChanges.spentOutputs()) {

                utxoStore.delete(
                        batch,
                        spent
                );
            }

            /*
             * Новый undo сохраняем только
             * для подключаемой ветки.
             *
             * Undo отключённых блоков НЕ удаляем.
             */
            for (Map.Entry<Hash256, BlockUndoData> entry
                    : changes.connectedBlockUndo()
                    .entrySet()) {

                undoStore.save(
                        batch,
                        entry.getKey(),
                        entry.getValue()
                );
                availability.markUndo(batch, entry.getKey());
                // Reaching this commit means full contextual/script validation for the
                // connected block succeeded. Persist that fact for restart-safe fork diagnostics.
                validationStatus.markScriptsValid(batch, entry.getKey());
            }

            /*
             * Active tip меняется ровно ОДИН раз
             * и сразу на конечный newTip.
             */
            chainStateStore.saveActiveTipHash(
                    batch,
                    newTip
            );

            /*
             * Один atomic + sync RocksDB commit.
             */
            database.write(
                    batch
            );
        }
    }
}
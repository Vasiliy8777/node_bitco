package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.RocksDbChainTransitionStorage;
import ru.bitcoin.node.chain.utxo.BlockReorganizationChanges;
import ru.bitcoin.node.common.types.Hash256;

public final class ChainTransitionManager {

    private final ChainState chainState;
    private final RocksDbChainTransitionStorage transitionStorage;

    public ChainTransitionManager(
            ChainState chainState,
            RocksDbChainTransitionStorage transitionStorage
    ) {
        if (chainState == null) {
            throw new IllegalArgumentException(
                    "chainState must not be null"
            );
        }

        if (transitionStorage == null) {
            throw new IllegalArgumentException(
                    "transitionStorage must not be null"
            );
        }

        this.chainState = chainState;
        this.transitionStorage = transitionStorage;
    }

    public ChainState chainState() {
        return chainState;
    }

    public void commit(
            ChainUpdate update,
            BlockReorganizationChanges changes
    ) {
        if (update == null) {
            throw new IllegalArgumentException(
                    "update must not be null"
            );
        }

        if (changes == null) {
            throw new IllegalArgumentException(
                    "changes must not be null"
            );
        }

        Hash256 expectedOldTip =
                update.oldTip().hash();

        Hash256 newTip =
                update.newTip().hash();

        /*
         * Сначала проверяем RAM-состояние.
         *
         * Если update stale уже относительно памяти,
         * диск вообще трогать нельзя.
         */
        if (!chainState.activeTip()
                .equals(update.oldTip())) {

            throw new IllegalStateException(
                    "Chain update is stale. "
                            + "Expected active tip: "
                            + update.oldTip()
                            .hash()
                            .toDisplayHex()
                            + ", actual: "
                            + chainState.activeTip()
                            .hash()
                            .toDisplayHex()
            );
        }

        /*
         * Сначала atomic durable commit на диск.
         */
        transitionStorage.commit(
                expectedOldTip,
                newTip,
                changes
        );

        /*
         * И только после успешного RocksDB commit
         * переключаем RAM.
         */
        try {
            chainState.commit(update);
        } catch (RuntimeException exception) {

            /*
             * На диске уже новое корректное состояние,
             * поэтому продолжать работу с устаревшим RAM
             * нельзя.
             *
             * Сейчас явно поднимаем fatal-style exception.
             * На уровне app позже это приведёт к остановке
             * ноды и восстановлению состояния с диска
             * при следующем запуске.
             */
            throw new IllegalStateException(
                    "Persistent chain transition succeeded, "
                            + "but in-memory ChainState commit failed. "
                            + "Node state must be reloaded from disk.",
                    exception
            );
        }
    }
}
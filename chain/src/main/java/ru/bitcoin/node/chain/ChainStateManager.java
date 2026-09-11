package ru.bitcoin.node.chain;

import ru.bitcoin.node.storage.chain.ChainStateStore;

public final class ChainStateManager {

    private final ChainState chainState;
    private final ChainStateStore chainStateStore;

    public ChainStateManager(
            ChainState chainState,
            ChainStateStore chainStateStore
    ) {
        if (chainState == null) {
            throw new IllegalArgumentException(
                    "chainState must not be null"
            );
        }

        if (chainStateStore == null) {
            throw new IllegalArgumentException(
                    "chainStateStore must not be null"
            );
        }

        this.chainState = chainState;
        this.chainStateStore = chainStateStore;
    }

    public ChainState chainState() {
        return chainState;
    }

    public void commit(
            ChainUpdate update
    ) {
        if (update == null) {
            throw new IllegalArgumentException(
                    "update must not be null"
            );
        }

        /*
         * Сначала проверяем и меняем состояние
         * активной цепи в памяти.
         */
        chainState.commit(
                update
        );

        /*
         * Затем сохраняем новую вершину
         * активной цепи на диск.
         */
        chainStateStore.saveActiveTipHash(
                update.newTip().hash()
        );
    }
}
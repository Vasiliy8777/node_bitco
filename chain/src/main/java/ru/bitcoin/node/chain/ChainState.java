package ru.bitcoin.node.chain;

public final class ChainState {

    private BlockIndex activeTip;

    public ChainState(
            BlockIndex initialTip
    ) {
        if (initialTip == null) {
            throw new IllegalArgumentException(
                    "initialTip must not be null"
            );
        }

        this.activeTip = initialTip;
    }

    public BlockIndex activeTip() {
        return activeTip;
    }

    /**
     * Проверяет candidate и, если он представляет
     * более сильную цепочку, строит план перехода.
     *
     * activeTip здесь НЕ изменяется.
     */
    public ChainUpdate prepareUpdate(
            BlockIndex candidate,
            BlockIndexLookup lookup
    ) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "candidate must not be null"
            );
        }

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        BlockIndex best =
                ChainSelector.selectBest(
                        activeTip,
                        candidate
                );

        /*
         * Текущая цепь всё ещё лучшая
         * или chainWork одинаков.
         */
        if (best == activeTip) {
            return null;
        }

        ReorganizationPlan plan =
                ReorganizationPlanner.plan(
                        activeTip,
                        candidate,
                        lookup
                );

        return new ChainUpdate(
                activeTip,
                candidate,
                plan
        );
    }

    /**
     * Фиксирует уже успешно применённый переход.
     *
     * Этот метод должен вызываться только после того,
     * как disconnect/connect и изменение UTXO
     * завершились успешно.
     */
    public void commit(
            ChainUpdate update
    ) {
        if (update == null) {
            throw new IllegalArgumentException(
                    "update must not be null"
            );
        }

        /*
         * Защита от устаревшего ChainUpdate.
         *
         * Пока мы применяли update,
         * состояние active chain могло измениться.
         */
        if (!activeTip.hash().equals(
                update.oldTip().hash()
        )) {
            throw new IllegalStateException(
                    "Active tip changed before commit"
            );
        }

        activeTip = update.newTip();
    }
}
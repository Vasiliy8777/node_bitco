package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.utxo.CreatedUtxo;
import ru.bitcoin.node.storage.utxo.StoredUtxo;
import ru.bitcoin.node.storage.utxo.UtxoChanges;
import ru.bitcoin.node.storage.utxo.UtxoStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class UtxoOverlay {

    private final UtxoStore baseStore;

    /*
     * Состояние UTXO ДО первого изменения данного OutPoint.
     *
     * Optional.empty() означает:
     * UTXO не существовал в persistent UTXO-set.
     */
    private final Map<OutPoint, Optional<StoredUtxo>>
            originalStates = new LinkedHashMap<>();

    /*
     * Текущее состояние OutPoint внутри обрабатываемого блока.
     *
     * Optional.empty() означает:
     * UTXO уже потрачен / отсутствует.
     */
    private final Map<OutPoint, Optional<StoredUtxo>>
            currentStates = new LinkedHashMap<>();

    public UtxoOverlay(
            UtxoStore baseStore
    ) {
        if (baseStore == null) {
            throw new IllegalArgumentException(
                    "baseStore must not be null"
            );
        }

        this.baseStore = baseStore;
    }

    public Optional<StoredUtxo> find(
            OutPoint outPoint
    ) {
        validateOutPoint(outPoint);

        if (currentStates.containsKey(outPoint)) {
            return currentStates.get(outPoint);
        }

        return baseStore.find(outPoint);
    }

    /**
     * Тратит UTXO и возвращает Coin,
     * который существовал непосредственно перед spend.
     *
     * Возвращаемое значение позже понадобится для UndoData.
     */
    public StoredUtxo spend(
            OutPoint outPoint
    ) {
        validateOutPoint(outPoint);

        snapshotOriginalState(outPoint);

        StoredUtxo existing =
                find(outPoint)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "UTXO does not exist: "
                                                        + outPoint
                                        )
                        );

        currentStates.put(
                outPoint,
                Optional.empty()
        );

        return existing;
    }

    /**
     * Устанавливает новое текущее состояние UTXO.
     *
     * Этот метод намеренно не реализует Bitcoin consensus
     * правила duplicate txid / BIP30.
     *
     * Overlay — механизм состояния.
     * Разрешено ли конкретное создание OutPoint,
     * должен решить consensus/chain validation layer.
     */
    public void put(
            OutPoint outPoint,
            StoredUtxo utxo
    ) {
        validateOutPoint(outPoint);

        if (utxo == null) {
            throw new IllegalArgumentException(
                    "utxo must not be null"
            );
        }

        snapshotOriginalState(outPoint);

        currentStates.put(
                outPoint,
                Optional.of(utxo)
        );
    }

    /**
     * Формирует NET delta между persistent UTXO-set
     * до блока и итоговым состоянием после всех
     * транзакций блока.
     *
     * Поэтому transient output:
     *
     *   create -> spend
     *
     * вообще отсутствует в результате.
     */
    public UtxoChanges changes() {

        List<OutPoint> spentOutputs =
                new ArrayList<>();

        List<CreatedUtxo> createdOutputs =
                new ArrayList<>();

        for (Map.Entry<OutPoint, Optional<StoredUtxo>> entry
                : originalStates.entrySet()) {

            OutPoint outPoint =
                    entry.getKey();

            Optional<StoredUtxo> original =
                    entry.getValue();

            Optional<StoredUtxo> current =
                    currentStates.get(outPoint);

            if (current == null) {
                throw new IllegalStateException(
                        "Missing current state for touched OutPoint: "
                                + outPoint
                );
            }

            /*
             * До блока UTXO отсутствовал,
             * после блока тоже отсутствует.
             *
             * Например:
             *
             * TX1 creates A:0
             * TX2 spends  A:0
             *
             * Persistent DB менять не надо.
             */
            if (original.isEmpty()
                    && current.isEmpty()) {

                continue;
            }

            /*
             * UTXO существовал до блока,
             * но после блока отсутствует.
             */
            if (original.isPresent()
                    && current.isEmpty()) {

                spentOutputs.add(
                        outPoint
                );

                continue;
            }

            /*
             * После блока UTXO существует.
             *
             * Это может быть:
             *
             * 1. совершенно новый output;
             * 2. replacement состояния того же OutPoint.
             *
             * Для RocksDB достаточно PUT:
             * существующее значение будет заменено.
             */
            if (current.isPresent()) {

                StoredUtxo currentUtxo =
                        current.orElseThrow();

                /*
                 * Если итог полностью совпадает
                 * с исходным состоянием,
                 * persistent mutation не нужна.
                 */
                if (original.isPresent()
                        && original.get()
                        .equals(currentUtxo)) {

                    continue;
                }

                createdOutputs.add(
                        new CreatedUtxo(
                                outPoint,
                                currentUtxo
                        )
                );
            }
        }

        return new UtxoChanges(
                spentOutputs,
                createdOutputs
        );
    }

    private void snapshotOriginalState(
            OutPoint outPoint
    ) {
        if (originalStates.containsKey(
                outPoint
        )) {
            return;
        }

        Optional<StoredUtxo> original =
                baseStore.find(outPoint);

        originalStates.put(
                outPoint,
                original
        );

        currentStates.put(
                outPoint,
                original
        );
    }

    private static void validateOutPoint(
            OutPoint outPoint
    ) {
        if (outPoint == null) {
            throw new IllegalArgumentException(
                    "outPoint must not be null"
            );
        }
    }
    public void apply(
            UtxoChanges changes
    ) {
        if (changes == null) {
            throw new IllegalArgumentException(
                    "changes must not be null"
            );
        }

        /*
         * Сначала PUT, затем DELETE.
         *
         * Это принципиально для rollback случая:
         *
         * TX1 creates A:0
         * TX2 spends  A:0
         *
         * disconnect:
         * restore A:0
         * delete  A:0
         *
         * Итог: A:0 отсутствует.
         */
        for (CreatedUtxo created
                : changes.createdOutputs()) {

            put(
                    created.outPoint(),
                    created.utxo()
            );
        }

        for (OutPoint spent
                : changes.spentOutputs()) {

            spend(spent);
        }
    }
}
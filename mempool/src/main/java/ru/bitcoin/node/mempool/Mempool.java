package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.consensus.transaction.UtxoView;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class Mempool {
    private final MempoolPolicy policy;
    private final Map<Hash256, MempoolEntry> entries =
            new ConcurrentHashMap<>();

    public Mempool() {
        this(
                new MempoolPolicy()
        );
    }

    public Mempool(
            MempoolPolicy policy
    ) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "policy must not be null"
            );
        }

        this.policy =
                policy;
    }

    /**
     * Единственная публичная точка добавления
     * новой транзакции в mempool.
     *
     * Сначала выполняется validation,
     * и только после успешной проверки
     * создаётся MempoolEntry.
     */
    public MempoolEntry admit(
            Transaction transaction,
            long spendingHeight,
            UtxoView utxoView
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        Hash256 txId =
                transaction.txId();

        /*
         * Быстрая предварительная проверка.
         *
         * Окончательная защита от race condition
         * ниже всё равно делается через putIfAbsent.
         */
        if (entries.containsKey(txId)) {
            throw new MempoolAdmissionException(
                    "Transaction already exists in mempool: "
                            + txId.toDisplayHex()
            );
        }

        /*
         * Consensus/contextual + standard policy.
         *
         * Если здесь произойдёт exception,
         * состояние mempool вообще не изменится.
         */
        MempoolValidator.validate(
                transaction,
                spendingHeight,
                utxoView
        );

        long fee =
                calculateFee(
                        transaction,
                        utxoView
                );

        long arrivalTime =
                Instant.now()
                        .getEpochSecond();

        long weight =
                TransactionWeight.calculate(
                        transaction
                );

        policy.validateStandardStructure(
                transaction,
                weight
        );

        policy.validateFee(
                fee,
                weight
        );

        MempoolEntry entry =
                new MempoolEntry(
                        transaction,
                        fee,
                        weight,
                        arrivalTime
                );

        MempoolEntry existing =
                entries.putIfAbsent(
                        txId,
                        entry
                );

        /*
         * Между containsKey() и putIfAbsent()
         * другая thread могла добавить ту же tx.
         */
        if (existing != null) {
            throw new MempoolAdmissionException(
                    "Transaction already exists in mempool: "
                            + txId.toDisplayHex()
            );
        }

        return entry;
    }

    public boolean contains(
            Hash256 txId
    ) {
        if (txId == null) {
            throw new IllegalArgumentException(
                    "txId must not be null"
            );
        }

        return entries.containsKey(
                txId
        );
    }

    public Optional<MempoolEntry> find(
            Hash256 txId
    ) {
        if (txId == null) {
            throw new IllegalArgumentException(
                    "txId must not be null"
            );
        }

        return Optional.ofNullable(
                entries.get(
                        txId
                )
        );
    }

    public Optional<MempoolEntry> remove(
            Hash256 txId
    ) {
        if (txId == null) {
            throw new IllegalArgumentException(
                    "txId must not be null"
            );
        }

        return Optional.ofNullable(
                entries.remove(
                        txId
                )
        );
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Immutable snapshot текущего состояния.
     *
     * Возвращаем именно snapshot, а не внутреннюю
     * mutable map.
     */
    public List<MempoolEntry> entries() {
        return List.copyOf(
                entries.values()
        );
    }

    private static long calculateFee(
            Transaction transaction,
            UtxoView utxoView
    ) {
        long totalInput =
                0L;

        for (TxIn input : transaction.inputs()) {

            UtxoEntry utxo =
                    utxoView.find(
                                    input.previousOutput()
                            )
                            .orElseThrow(
                                    () ->
                                            new MempoolAdmissionException(
                                                    "Missing UTXO while calculating fee: "
                                                            + input.previousOutput()
                                            )
                            );

            totalInput =
                    Math.addExact(
                            totalInput,
                            utxo.amount()
                    );
        }

        long totalOutput =
                0L;

        for (TxOut output : transaction.outputs()) {

            totalOutput =
                    Math.addExact(
                            totalOutput,
                            output.value()
                    );
        }

        return Math.subtractExact(
                totalInput,
                totalOutput
        );
    }
}
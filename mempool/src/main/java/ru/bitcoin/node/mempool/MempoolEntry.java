package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.Transaction;

public record MempoolEntry(
        Transaction transaction,
        long fee,
        long weight,
        long arrivalTime
) {

    public long virtualSize() {
        return TransactionWeight.virtualSize(
                weight
        );
    }

    public FeeRate feeRate() {
        return FeeRate.fromFeeAndVSize(
                fee,
                virtualSize()
        );
    }
}
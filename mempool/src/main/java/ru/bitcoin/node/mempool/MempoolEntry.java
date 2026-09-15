package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.Transaction;

public record MempoolEntry(
        Transaction transaction,
        long fee,
        long weight,
        long arrivalTime,
        long sigOpCost
) {

    public MempoolEntry(Transaction transaction, long fee, long weight, long arrivalTime) {
        this(transaction, fee, weight, arrivalTime, 0);
    }

    public long virtualSize() {
        return TransactionWeight.virtualSize(
                        Math.max(weight, Math.multiplyExact(sigOpCost, 20))
        );
    }

    public FeeRate feeRate() {
        return FeeRate.fromFeeAndVSize(
                fee,
                virtualSize()
        );
    }
}

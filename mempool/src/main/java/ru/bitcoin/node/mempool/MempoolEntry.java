package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.Transaction;

/** Base consensus fee plus local policy/mining fee delta. */
public record MempoolEntry(
        Transaction transaction,
        long fee,
        long weight,
        long arrivalTime,
        long sigOpCost,
        long feeDelta,
        long admissionHeight
) {
    public MempoolEntry(Transaction transaction, long fee, long weight, long arrivalTime) {
        this(transaction, fee, weight, arrivalTime, 0, 0, 0);
    }

    public MempoolEntry(Transaction transaction, long fee, long weight, long arrivalTime, long sigOpCost) {
        this(transaction, fee, weight, arrivalTime, sigOpCost, 0, 0);
    }

    public MempoolEntry(Transaction transaction, long fee, long weight, long arrivalTime, long sigOpCost, long feeDelta) {
        this(transaction, fee, weight, arrivalTime, sigOpCost, feeDelta, 0);
    }

    /** Fee used only by mempool policy and mining. Consensus/base accounting keeps fee(). */
    public long modifiedFee() {
        return Math.addExact(fee, feeDelta);
    }

    public MempoolEntry withFeeDelta(long delta) {
        return new MempoolEntry(transaction, fee, weight, arrivalTime, sigOpCost, delta, admissionHeight);
    }

    /** Bitcoin Core-style sigops-adjusted weight used by cluster feerate calculations. */
    public long adjustedWeight() {
        return Math.max(weight, Math.multiplyExact(sigOpCost, 80));
    }

    public long virtualSize() {
        return TransactionWeight.virtualSize(adjustedWeight());
    }

    public FeeRate feeRate() {
        return FeeRate.fromFeeAndVSize(modifiedFee(), virtualSize());
    }
}

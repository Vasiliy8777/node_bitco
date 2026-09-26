package ru.bitcoin.node.storage.mempool;

import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.Objects;

/**
 * Transaction plus local admission time persisted across clean or crash restarts.
 */
public record PersistedMempoolEntry(Transaction transaction, long arrivalTime) {
    public PersistedMempoolEntry {
        Objects.requireNonNull(transaction, "transaction");
        if (arrivalTime < 0) throw new IllegalArgumentException("arrivalTime must not be negative");
    }
}

package ru.bitcoin.node.storage.mempool;

import ru.bitcoin.node.protocol.transaction.Transaction;
import java.util.Objects;

/** Transaction plus local metadata persisted across restarts/imports. */
public record PersistedMempoolEntry(Transaction transaction, long arrivalTime, long admissionHeight, boolean unbroadcast) {
    public PersistedMempoolEntry {
        Objects.requireNonNull(transaction, "transaction");
        if (arrivalTime < 0 || admissionHeight < 0) throw new IllegalArgumentException("negative mempool metadata");
    }
    public PersistedMempoolEntry(Transaction transaction, long arrivalTime) {
        this(transaction, arrivalTime, 0L, false);
    }
}

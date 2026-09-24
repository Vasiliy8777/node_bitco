package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.Objects;

public record PrefilledTransaction(int index, Transaction transaction) {
    public PrefilledTransaction {
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        Objects.requireNonNull(transaction, "transaction");
    }
}

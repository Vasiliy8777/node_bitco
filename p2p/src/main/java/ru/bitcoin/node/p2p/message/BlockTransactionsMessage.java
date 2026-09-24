package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.*;

public record BlockTransactionsMessage(Hash256 blockHash, List<Transaction> transactions) {
    public BlockTransactionsMessage {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(transactions, "transactions");
        transactions = List.copyOf(transactions);
        if (transactions.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("transactions contain null");
    }
}

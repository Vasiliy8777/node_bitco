package ru.bitcoin.node.mempool;


import ru.bitcoin.node.protocol.transaction.Transaction;

public record MempoolEntry(
        Transaction transaction,
        long fee,
        long weight,
        long arrivalTime
) {}

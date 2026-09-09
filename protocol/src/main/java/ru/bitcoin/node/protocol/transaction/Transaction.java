package ru.bitcoin.node.protocol.transaction;

import java.util.List;

public record Transaction(
        int version,
        List<TransactionInput> inputs,
        List<TransactionOutput> outputs,
        long lockTime
) {}
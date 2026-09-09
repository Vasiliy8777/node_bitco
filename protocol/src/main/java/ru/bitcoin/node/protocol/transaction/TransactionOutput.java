package ru.bitcoin.node.protocol.transaction;

public record TransactionOutput(
        long value,
        byte[] scriptPubKey
) {}
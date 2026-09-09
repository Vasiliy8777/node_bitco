package ru.bitcoin.node.protocol.transaction;

public record OutPoint(
        byte[] txId,
        long outputIndex
) {}

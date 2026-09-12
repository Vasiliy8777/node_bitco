package ru.bitcoin.node.consensus.transaction;

public record TransactionContextResult(
        long inputValue,
        long outputValue,
        long fee
) {
}
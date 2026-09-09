package ru.bitcoin.node.protocol.transaction;

public record TransactionInput(
        OutPoint previousOutput,
        byte[] scriptSig,
        long sequence,
        Witness witness
) {}

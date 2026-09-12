package ru.bitcoin.node.consensus.transaction;

public final class TransactionValidationException
        extends RuntimeException {

    public TransactionValidationException(String message) {
        super(message);
    }
}
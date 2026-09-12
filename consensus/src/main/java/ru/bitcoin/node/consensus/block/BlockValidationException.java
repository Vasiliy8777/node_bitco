package ru.bitcoin.node.consensus.block;

public final class BlockValidationException
        extends RuntimeException {

    public BlockValidationException(String message) {
        super(message);
    }
}
package ru.bitcoin.node.consensus.block;

public final class BlockHeaderValidationException
        extends RuntimeException {

    public BlockHeaderValidationException(
            String message
    ) {
        super(message);
    }
}
package ru.bitcoin.node.consensus.block;

public final class BlockSigOpsValidator {
    public static final long MAX_BLOCK_SIGOPS_COST = 80_000;
    private BlockSigOpsValidator() { }

    public static void validate(long cost) {
        if (cost < 0 || cost > MAX_BLOCK_SIGOPS_COST) {
            throw new BlockValidationException("Block exceeds sigops cost limit: " + cost);
        }
    }
}

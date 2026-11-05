package ru.bitcoin.node.consensus.block;

public final class BlockHeaderValidationException
        extends RuntimeException {

    public enum Reason { INVALID, CACHED_INVALID, TIME_FUTURE }

    private final Reason reason;

    public BlockHeaderValidationException(
            String message
    ) {
        this(message, Reason.INVALID);
    }

    public BlockHeaderValidationException(String message, Reason reason) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}

package ru.bitcoin.node.consensus.block;

public final class BlockHeaderValidationException
        extends RuntimeException {

    public enum Reason { INVALID, CACHED_INVALID, TIME_FUTURE }

    private final Reason reason;
    private final String rejectReason;

    public BlockHeaderValidationException(
            String message
    ) {
        this(message, Reason.INVALID);
    }

    public BlockHeaderValidationException(String message, Reason reason) {
        this(message, reason, reason == Reason.TIME_FUTURE ? "time-too-new" : reason == Reason.CACHED_INVALID ? "duplicate-invalid" : "invalid");
    }

    public BlockHeaderValidationException(String message, String rejectReason) {
        this(message, Reason.INVALID, rejectReason);
    }

    public BlockHeaderValidationException(String message, Reason reason, String rejectReason) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
        this.rejectReason = java.util.Objects.requireNonNull(rejectReason, "rejectReason");
    }

    public Reason reason() { return reason; }
    public String rejectReason() { return rejectReason; }
}

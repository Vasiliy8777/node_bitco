package ru.bitcoin.node.mempool;

/** Core validation-state reason/debug pair, independent of legacy internal diagnostics. */
public final class MempoolPolicyException extends MempoolAdmissionException {
    private final String reason;
    private final String details;
    public MempoolPolicyException(String diagnostic, String reason, String debug) {
        super(diagnostic); this.reason = reason;
        this.details = debug == null || debug.isEmpty() ? reason : reason + ", " + debug;
    }
    public String reason() { return reason; }
    public String details() { return details; }
}

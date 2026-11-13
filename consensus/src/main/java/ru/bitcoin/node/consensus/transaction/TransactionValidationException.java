package ru.bitcoin.node.consensus.transaction;

public final class TransactionValidationException
        extends RuntimeException {

    private final String reason;
    private final String debug;
    public TransactionValidationException(String message) { this(message,null,null); }
    public TransactionValidationException(String message,String reason,String debug) {
        super(message);this.reason=reason;this.debug=debug;
    }
    public String reason() { return reason==null?getMessage():reason; }
    public String debugMessage() { return debug; }
    public String details() { return debug==null || debug.isEmpty()?reason():reason()+", "+debug; }
}

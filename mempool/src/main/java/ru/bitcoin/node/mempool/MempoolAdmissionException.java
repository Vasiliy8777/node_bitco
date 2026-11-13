package ru.bitcoin.node.mempool;

public class MempoolAdmissionException extends RuntimeException {
    /** Classification independent of diagnostic text for broadcast RPCs. */
    public boolean isMissingInputs() { return false; }

    public MempoolAdmissionException(
            String message
    ) {
        super(message);
    }
}

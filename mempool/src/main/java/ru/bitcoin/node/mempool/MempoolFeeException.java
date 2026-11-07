package ru.bitcoin.node.mempool;

/** Structured fee rejection; diagnostic wording is not parsed by RPC. */
final class MempoolFeeException extends MempoolAdmissionException {
    private final String details;
    MempoolFeeException(String diagnostic, String rpcReason, long paid, long required) {
        super(diagnostic);
        details = rpcReason + ", " + paid + " < " + required;
    }
    String rpcDetails() { return details; }
}

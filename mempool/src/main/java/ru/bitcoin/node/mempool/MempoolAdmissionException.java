package ru.bitcoin.node.mempool;

public class MempoolAdmissionException extends RuntimeException {

    public MempoolAdmissionException(
            String message
    ) {
        super(message);
    }
}
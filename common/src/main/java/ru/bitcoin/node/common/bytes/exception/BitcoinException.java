package ru.bitcoin.node.common.bytes.exception;

public class BitcoinException extends RuntimeException {

    public BitcoinException(String message) {
        super(message);
    }

    public BitcoinException(String message, Throwable cause) {
        super(message, cause);
    }

    public BitcoinException(Throwable cause) {
        super(cause);
    }
}
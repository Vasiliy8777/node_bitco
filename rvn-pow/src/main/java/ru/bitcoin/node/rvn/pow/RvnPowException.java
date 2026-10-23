package ru.bitcoin.node.rvn.pow;

public final class RvnPowException extends Exception {
    public RvnPowException(String message) {
        super(message);
    }

    public RvnPowException(String message, Throwable cause) {
        super(message, cause);
    }
}

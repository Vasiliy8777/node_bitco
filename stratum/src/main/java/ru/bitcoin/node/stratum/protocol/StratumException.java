package ru.bitcoin.node.stratum.protocol;

public final class StratumException extends RuntimeException {
    private final int code;
    public StratumException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
}

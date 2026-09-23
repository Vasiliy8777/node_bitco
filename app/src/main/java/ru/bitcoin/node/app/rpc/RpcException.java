package ru.bitcoin.node.app.rpc;

public final class RpcException extends RuntimeException {
    private final int code;
    public RpcException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
}

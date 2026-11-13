package ru.bitcoin.node.script;

public class ScriptExecutionException
        extends RuntimeException {
    private final ScriptError code;
    public ScriptExecutionException(ScriptError code, String message) { super(message); this.code = code; }
    public ScriptError code() { return code; }
    public String error() { return code.description(); }

    public ScriptExecutionException(
            String message
    ) {
        super(message);
        this.code = ScriptError.UNKNOWN_ERROR;
    }

    public ScriptExecutionException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.code = ScriptError.UNKNOWN_ERROR;
    }
}

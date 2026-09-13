package ru.bitcoin.node.script;

public class ScriptExecutionException
        extends RuntimeException {

    public ScriptExecutionException(
            String message
    ) {
        super(message);
    }

    public ScriptExecutionException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
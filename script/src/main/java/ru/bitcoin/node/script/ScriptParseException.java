package ru.bitcoin.node.script;

public class ScriptParseException
        extends RuntimeException {

    public ScriptParseException(
            String message
    ) {
        super(message);
    }

    public ScriptParseException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
package ru.bitcoin.node.script;

public class ScriptParseException
        extends RuntimeException {
    public ScriptError code() { return ScriptError.BAD_OPCODE; }
    public String error() { return code().description(); }

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

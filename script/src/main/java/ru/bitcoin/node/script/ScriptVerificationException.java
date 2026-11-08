package ru.bitcoin.node.script;

/** Script failure with the stable ScriptErrorString used by Bitcoin Core. */
public final class ScriptVerificationException extends ScriptExecutionException {
    private final String error;
    public ScriptVerificationException(String error) { this(error, error); }
    public ScriptVerificationException(String error, String message) { super(message); this.error = error; }
    public String error() { return error; }
}

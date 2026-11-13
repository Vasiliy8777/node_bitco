package ru.bitcoin.node.script;

/** Script failure with the stable ScriptErrorString used by Bitcoin Core. */
public final class ScriptVerificationException extends ScriptExecutionException {
    private final String error;
    private final ScriptError code;
    public ScriptVerificationException(String error) { this(error, error); }
    public ScriptVerificationException(String error, String message) { super(message); this.error = error; this.code = ScriptError.fromDescription(error); }
    public ScriptVerificationException(ScriptError error) { this(error, error.description()); }
    public ScriptVerificationException(ScriptError error, String message) { super(message); this.error = error.description(); this.code = error; }
    public String error() { return error; }
    public ScriptError code() { return code; }
}

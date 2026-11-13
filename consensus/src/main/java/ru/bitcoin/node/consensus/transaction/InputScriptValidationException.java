package ru.bitcoin.node.consensus.transaction;

import ru.bitcoin.node.script.ScriptError;
import ru.bitcoin.node.script.ScriptExecutionException;

/** Ordered CScriptCheck-style result from the parallel input verification queue. */
public final class InputScriptValidationException extends ScriptExecutionException {
    private final int inputIndex;
    private final ScriptError code;
    private final String description;
    public InputScriptValidationException(int inputIndex, ScriptError code, String description) {
        super(code, description); this.inputIndex = inputIndex; this.code = code; this.description = description;
    }
    public int inputIndex() { return inputIndex; }
    public ScriptError code() { return code; }
    public String description() { return description; }
}

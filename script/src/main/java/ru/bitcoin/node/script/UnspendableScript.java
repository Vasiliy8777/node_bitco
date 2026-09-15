package ru.bitcoin.node.script;

import java.util.Objects;

/** Core's IsUnspendable predicate for excluding outputs from the UTXO set.
 * This is not a general script satisfiability check.
 */
public final class UnspendableScript {
    private UnspendableScript() { }

    public static boolean isUnspendable(byte[] script) {
        Objects.requireNonNull(script, "script");
        return script.length > ScriptLimits.MAX_SCRIPT_SIZE
                || (script.length > 0 && Byte.toUnsignedInt(script[0]) == Opcode.OP_RETURN);
    }
}

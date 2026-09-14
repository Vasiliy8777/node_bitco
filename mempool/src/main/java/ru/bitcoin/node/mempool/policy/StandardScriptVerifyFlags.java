package ru.bitcoin.node.mempool.policy;

import ru.bitcoin.node.script.ScriptVerifyFlags;

public final class StandardScriptVerifyFlags {

    /*
     * Flags, которые наша нода сейчас использует
     * как обязательную базу для стандартных
     * mempool transactions.
     *
     * ВАЖНО:
     * это НЕ replacement для contextual
     * ConsensusScriptFlags.forBlock().
     *
     * Block validation продолжает получать flags
     * из consensus activation logic.
     */
    public static final int MANDATORY =
            ScriptVerifyFlags.P2SH
                    | ScriptVerifyFlags.DERSIG
                    | ScriptVerifyFlags.NULLDUMMY
                    | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY
                    | ScriptVerifyFlags.CHECKSEQUENCEVERIFY
                    | ScriptVerifyFlags.WITNESS;

    /*
     * Дополнительные standardness / relay policy
     * проверки, не являющиеся отдельными
     * историческими block-consensus activations.
     */
    public static final int STANDARD_NOT_MANDATORY =
            ScriptVerifyFlags.STRICTENC
                    | ScriptVerifyFlags.MINIMALDATA
                    | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                    | ScriptVerifyFlags.CLEANSTACK
                    | ScriptVerifyFlags.MINIMALIF
                    | ScriptVerifyFlags.NULLFAIL
                    | ScriptVerifyFlags.LOW_S
                    | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                    | ScriptVerifyFlags.WITNESS_PUBKEYTYPE
                    | ScriptVerifyFlags.CONST_SCRIPTCODE;

    public static final int STANDARD =
            MANDATORY
                    | STANDARD_NOT_MANDATORY;

    private StandardScriptVerifyFlags() {
    }
}
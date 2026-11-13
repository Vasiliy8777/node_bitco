package ru.bitcoin.node.script;

/** Bitcoin Core v32.1 ScriptError and ScriptErrorString, including diagnostic-only failures. */
public enum ScriptError {
    OK("No error"),
    EVAL_FALSE("Script evaluated without error but finished with a false/empty top stack element"),
    VERIFY("Script failed an OP_VERIFY operation"),
    EQUALVERIFY("Script failed an OP_EQUALVERIFY operation"),
    CHECKMULTISIGVERIFY("Script failed an OP_CHECKMULTISIGVERIFY operation"),
    CHECKSIGVERIFY("Script failed an OP_CHECKSIGVERIFY operation"),
    NUMEQUALVERIFY("Script failed an OP_NUMEQUALVERIFY operation"),
    SCRIPT_SIZE("Script is too big"),
    PUSH_SIZE("Push value size limit exceeded"),
    OP_COUNT("Operation limit exceeded"),
    STACK_SIZE("Stack size limit exceeded"),
    SIG_COUNT("Signature count negative or greater than pubkey count"),
    PUBKEY_COUNT("Pubkey count negative or limit exceeded"),
    BAD_OPCODE("Opcode missing or not understood"),
    DISABLED_OPCODE("Attempted to use a disabled opcode"),
    INVALID_STACK_OPERATION("Operation not valid with the current stack size"),
    INVALID_ALTSTACK_OPERATION("Operation not valid with the current altstack size"),
    OP_RETURN("OP_RETURN was encountered"),
    UNBALANCED_CONDITIONAL("Invalid OP_IF construction"),
    NEGATIVE_LOCKTIME("Negative locktime"),
    UNSATISFIED_LOCKTIME("Locktime requirement not satisfied"),
    SIG_HASHTYPE("Signature hash type missing or not understood"),
    SIG_DER("Non-canonical DER signature"),
    MINIMALDATA("Data push larger than necessary"),
    SIG_PUSHONLY("Only push operators allowed in signatures"),
    SIG_HIGH_S("Non-canonical signature: S value is unnecessarily high"),
    SIG_NULLDUMMY("Dummy CHECKMULTISIG argument must be zero"),
    MINIMALIF("OP_IF/NOTIF argument must be minimal"),
    SIG_NULLFAIL("Signature must be zero for failed CHECK(MULTI)SIG operation"),
    DISCOURAGE_UPGRADABLE_NOPS("NOPx reserved for soft-fork upgrades"),
    DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM("Witness version reserved for soft-fork upgrades"),
    DISCOURAGE_UPGRADABLE_TAPROOT_VERSION("Taproot version reserved for soft-fork upgrades"),
    DISCOURAGE_OP_SUCCESS("OP_SUCCESSx reserved for soft-fork upgrades"),
    DISCOURAGE_UPGRADABLE_PUBKEYTYPE("Public key version reserved for soft-fork upgrades"),
    PUBKEYTYPE("Public key is neither compressed or uncompressed"),
    CLEANSTACK("Stack size must be exactly one after execution"),
    WITNESS_PROGRAM_WRONG_LENGTH("Witness program has incorrect length"),
    WITNESS_PROGRAM_WITNESS_EMPTY("Witness program was passed an empty witness"),
    WITNESS_PROGRAM_MISMATCH("Witness program hash mismatch"),
    WITNESS_MALLEATED("Witness requires empty scriptSig"),
    WITNESS_MALLEATED_P2SH("Witness requires only-redeemscript scriptSig"),
    WITNESS_UNEXPECTED("Witness provided for non-witness script"),
    WITNESS_PUBKEYTYPE("Using non-compressed keys in segwit"),
    SCHNORR_SIG_SIZE("Invalid Schnorr signature size"),
    SCHNORR_SIG_HASHTYPE("Invalid Schnorr signature hash type"),
    SCHNORR_SIG("Invalid Schnorr signature"),
    TAPROOT_WRONG_CONTROL_SIZE("Invalid Taproot control block size"),
    TAPSCRIPT_VALIDATION_WEIGHT("Too much signature validation relative to witness weight"),
    TAPSCRIPT_CHECKMULTISIG("OP_CHECKMULTISIG(VERIFY) is not available in tapscript"),
    TAPSCRIPT_MINIMALIF("OP_IF/NOTIF argument must be minimal in tapscript"),
    TAPSCRIPT_EMPTY_PUBKEY("Empty public key in tapscript"),
    OP_CODESEPARATOR("Using OP_CODESEPARATOR in non-witness script"),
    SIG_FINDANDDELETE("Signature is found in scriptCode"),
    SCRIPTNUM("Script number overflowed or is non-minimally encoded"),
    UNKNOWN_ERROR("unknown error");

    private final String description;
    ScriptError(String description) { this.description = description; }
    public String description() { return description; }
    public static ScriptError fromDescription(String description) {
        for (var error : values()) if (error.description.equals(description)) return error;
        return UNKNOWN_ERROR;
    }
}


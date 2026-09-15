package ru.bitcoin.node.script;

public final class ScriptVerifyFlags {
    /** Explicit script verification option; not a block consensus activation.
     * Bit assignments are internal and must not be interpreted as Core's numeric mask.
     */
    public static final int SIGPUSHONLY = 1 << 20;
    public static final int TAPROOT = 1 << 16;
    public static final int DISCOURAGE_UPGRADABLE_TAPROOT_VERSION = 1 << 17;
    public static final int DISCOURAGE_OP_SUCCESS = 1 << 18;
    public static final int DISCOURAGE_UPGRADABLE_PUBKEYTYPE = 1 << 19;

    public static final int NONE =
            0;

    /*
     * BIP66:
     * strict DER encoding for ECDSA signatures.
     */
    public static final int DERSIG =
            1 << 0;

    /*
     * Standardness rule:
     * signature S must be <= curveOrder / 2.
     */
    public static final int LOW_S =
            1 << 1;

    /*
     * Strict signature hash type and public-key encoding.
     */
    public static final int STRICTENC =
            1 << 2;
    /*
     * BIP16 / P2SH.
     *
     * При активном флаге scriptPubKey вида
     *
     * OP_HASH160 <20-byte hash> OP_EQUAL
     *
     * требует дополнительного выполнения redeemScript,
     * переданного последним push в scriptSig.
     */
    public static final int P2SH =
            1 << 3;
    /*
     * BIP65 / OP_CHECKLOCKTIMEVERIFY.
     */
    public static final int CHECKLOCKTIMEVERIFY =
            1 << 4;
    /*
     * BIP112 / OP_CHECKSEQUENCEVERIFY.
     */
    public static final int CHECKSEQUENCEVERIFY =
            1 << 5;
    /*
     * BIP141 / SegWit witness program validation.
     */
    public static final int WITNESS =
            1 << 6;
    /*
     * BIP147 / NULLDUMMY.
     *
     * Исторический дополнительный dummy-элемент,
     * потребляемый OP_CHECKMULTISIG,
     * при активном флаге обязан быть пустым.
     */
    public static final int NULLDUMMY =
            1 << 7;
    /*
     * Require minimal encoding for:
     *
     * 1. executed push operations;
     * 2. stack elements interpreted as Script numbers.
     *
     * Это standardness/policy flag, а не отдельная
     * историческая consensus activation.
     */
    public static final int MINIMALDATA =
            1 << 8;
    public static final int MINIMALIF =
            1 << 9;
    public static final int NULLFAIL =
            1 << 10;
    public static final int CLEANSTACK =
            1 << 11;
    public static final int DISCOURAGE_UPGRADABLE_NOPS =
            1 << 12;
    public static final int WITNESS_PUBKEYTYPE =
            1 << 13;
    public static final int DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM =
            1 << 14;
    /*
     * Policy rule:
     *
     * legacy signature checking must not modify
     * scriptCode through historical FindAndDelete.
     * Legacy OP_CODESEPARATOR is also forbidden, even in an unexecuted branch.
     * A separator byte inside pushed data is not an opcode.
     *
     * Для WITNESS_V0 этот flag не применяется,
     * потому что BIP143 не использует FindAndDelete.
     */
    public static final int CONST_SCRIPTCODE =
            1 << 15;

    private ScriptVerifyFlags() {
    }

    /** Parse the symbolic flag lists used by Bitcoin Core test vectors.
     * Core numeric masks are intentionally not accepted: our bit assignments differ.
     */
    public static int parseNames(String names) {
        java.util.Objects.requireNonNull(names, "names");
        if (names.isBlank()) return NONE;
        int flags = NONE;
        for (String token : names.split(",", -1)) {
            flags |= switch (token.trim()) {
                case "NONE" -> NONE;
                case "P2SH" -> P2SH;
                case "STRICTENC" -> STRICTENC;
                case "DERSIG" -> DERSIG;
                case "LOW_S" -> LOW_S;
                case "NULLDUMMY" -> NULLDUMMY;
                case "SIGPUSHONLY" -> SIGPUSHONLY;
                case "MINIMALDATA" -> MINIMALDATA;
                case "DISCOURAGE_UPGRADABLE_NOPS" -> DISCOURAGE_UPGRADABLE_NOPS;
                case "CLEANSTACK" -> CLEANSTACK;
                case "CHECKLOCKTIMEVERIFY" -> CHECKLOCKTIMEVERIFY;
                case "CHECKSEQUENCEVERIFY" -> CHECKSEQUENCEVERIFY;
                case "WITNESS" -> WITNESS;
                case "DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM" -> DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM;
                case "MINIMALIF" -> MINIMALIF;
                case "NULLFAIL" -> NULLFAIL;
                case "WITNESS_PUBKEYTYPE" -> WITNESS_PUBKEYTYPE;
                case "CONST_SCRIPTCODE" -> CONST_SCRIPTCODE;
                case "TAPROOT" -> TAPROOT;
                case "DISCOURAGE_UPGRADABLE_TAPROOT_VERSION" -> DISCOURAGE_UPGRADABLE_TAPROOT_VERSION;
                case "DISCOURAGE_OP_SUCCESS" -> DISCOURAGE_OP_SUCCESS;
                case "DISCOURAGE_UPGRADABLE_PUBKEYTYPE" -> DISCOURAGE_UPGRADABLE_PUBKEYTYPE;
                default -> throw new IllegalArgumentException("Unknown Script verification flag: " + token);
            };
        }
        return flags;
    }

    public static boolean has(
            int flags,
            int flag
    ) {
        return (flags & flag) != 0;
    }
}

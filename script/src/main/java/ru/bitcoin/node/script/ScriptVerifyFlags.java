package ru.bitcoin.node.script;

public final class ScriptVerifyFlags {

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

    private ScriptVerifyFlags() {
    }

    public static boolean has(
            int flags,
            int flag
    ) {
        return (flags & flag) != 0;
    }
}
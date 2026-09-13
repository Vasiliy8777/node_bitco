package ru.bitcoin.node.script;

public final class SignatureHashType {

    public static final int SIGHASH_ALL = 0x01;
    public static final int SIGHASH_NONE = 0x02;
    public static final int SIGHASH_SINGLE = 0x03;

    public static final int SIGHASH_ANYONECANPAY = 0x80;

    public static final int BASE_TYPE_MASK = 0x1f;

    private SignatureHashType() {
    }

    public static int baseType(
            int hashType
    ) {
        return hashType & BASE_TYPE_MASK;
    }

    public static boolean isAnyoneCanPay(
            int hashType
    ) {
        return (hashType & SIGHASH_ANYONECANPAY) != 0;
    }
}
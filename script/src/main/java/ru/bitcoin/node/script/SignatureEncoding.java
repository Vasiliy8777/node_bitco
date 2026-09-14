package ru.bitcoin.node.script;

import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;

import java.util.Arrays;

public final class SignatureEncoding {

    /*
     * Minimum:
     *
     * 30 06
     * 02 01 01
     * 02 01 01
     * 01
     *
     * Последний 01 = sighash type.
     */
    public static final int MIN_TRANSACTION_SIGNATURE_LENGTH =
            9;

    /*
     * Max DER ECDSA signature = 72 bytes
     * + one sighash byte.
     */
    public static final int MAX_TRANSACTION_SIGNATURE_LENGTH =
            73;

    private SignatureEncoding() {
    }

    public static boolean isCompressedPublicKeyEncoding(
            byte[] publicKey
    ) {
        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        if (publicKey.length != 33) {
            return false;
        }

        int prefix =
                Byte.toUnsignedInt(
                        publicKey[0]
                );

        return prefix == 0x02
                || prefix == 0x03;
    }
    public static boolean isValidDerEncoding(
            byte[] signatureWithHashType
    ) {
        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        int size =
                signatureWithHashType.length;

        if (size < MIN_TRANSACTION_SIGNATURE_LENGTH
                || size > MAX_TRANSACTION_SIGNATURE_LENGTH) {

            return false;
        }

        /*
         * DER sequence.
         */
        if (Byte.toUnsignedInt(
                signatureWithHashType[0]
        ) != 0x30) {

            return false;
        }

        /*
         * DER length excludes:
         *
         * 30
         * length byte
         * sighash byte
         */
        if (Byte.toUnsignedInt(
                signatureWithHashType[1]
        ) != size - 3) {

            return false;
        }

        /*
         * R must start with INTEGER.
         */
        if (Byte.toUnsignedInt(
                signatureWithHashType[2]
        ) != 0x02) {

            return false;
        }

        int lengthR =
                Byte.toUnsignedInt(
                        signatureWithHashType[3]
                );

        /*
         * We must be able to reach:
         *
         * 30 len 02 lenR R... 02 lenS ...
         */
        if (5 + lengthR >= size) {
            return false;
        }

        /*
         * S INTEGER tag.
         */
        if (Byte.toUnsignedInt(
                signatureWithHashType[
                        lengthR + 4
                        ]
        ) != 0x02) {

            return false;
        }

        int lengthS =
                Byte.toUnsignedInt(
                        signatureWithHashType[
                                lengthR + 5
                                ]
                );

        /*
         * Complete transaction signature length:
         *
         * 6 bytes of DER structural fields
         * + R
         * + S
         * + 1 sighash byte.
         */
        if (lengthR + lengthS + 7
                != size) {

            return false;
        }

        if (lengthR == 0) {
            return false;
        }

        /*
         * R must not be negative.
         */
        if ((signatureWithHashType[4]
                & 0x80) != 0) {

            return false;
        }

        /*
         * R must use shortest possible encoding.
         *
         * Leading 00 is allowed only when required
         * to prevent the next byte looking negative.
         */
        if (lengthR > 1
                && signatureWithHashType[4] == 0x00
                && (
                signatureWithHashType[5]
                        & 0x80
        ) == 0) {

            return false;
        }

        if (lengthS == 0) {
            return false;
        }

        int sOffset =
                lengthR + 6;

        /*
         * S must not be negative.
         */
        if ((signatureWithHashType[sOffset]
                & 0x80) != 0) {

            return false;
        }

        /*
         * S must use shortest possible encoding.
         */
        if (lengthS > 1
                && signatureWithHashType[
                sOffset
                ] == 0x00
                && (
                signatureWithHashType[
                        sOffset + 1
                        ]
                        & 0x80
        ) == 0) {

            return false;
        }

        return true;
    }

    public static boolean isDefinedHashType(
            byte[] signatureWithHashType
    ) {
        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        if (signatureWithHashType.length == 0) {
            return false;
        }

        int hashType =
                Byte.toUnsignedInt(
                        signatureWithHashType[
                                signatureWithHashType.length - 1
                                ]
                );

        /*
         * Bitcoin semantics remove ONLY
         * SIGHASH_ANYONECANPAY here.
         *
         * Other unknown bits therefore remain
         * and make the type invalid under STRICTENC.
         */
        int baseType =
                hashType
                        & ~SignatureHashType.SIGHASH_ANYONECANPAY;

        return baseType
                >= SignatureHashType.SIGHASH_ALL
                && baseType
                <= SignatureHashType.SIGHASH_SINGLE;
    }

    public static boolean isLowS(
            byte[] signatureWithHashType
    ) {
        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        if (!isValidDerEncoding(
                signatureWithHashType
        )) {
            return false;
        }

        byte[] der =
                Arrays.copyOf(
                        signatureWithHashType,
                        signatureWithHashType.length - 1
                );

        EcdsaSignature signature =
                EcdsaSignature.fromDer(
                        der
                );

        return signature.isLowS();
    }

    public static boolean isStrictPublicKeyEncoding(
            byte[] publicKey
    ) {
        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        if (publicKey.length == 33) {

            int prefix =
                    Byte.toUnsignedInt(
                            publicKey[0]
                    );

            return prefix == 0x02
                    || prefix == 0x03;
        }

        if (publicKey.length == 65) {

            return Byte.toUnsignedInt(
                    publicKey[0]
            ) == 0x04;
        }

        return false;
    }

    public static void validateSignature(
            byte[] signatureWithHashType,
            int flags
    ) {
        if (signatureWithHashType == null) {
            throw new IllegalArgumentException(
                    "signatureWithHashType must not be null"
            );
        }

        /*
         * Critical Bitcoin behavior:
         *
         * Empty signature deliberately bypasses
         * encoding errors and simply makes
         * CHECKSIG evaluate to false.
         */
        if (signatureWithHashType.length == 0) {
            return;
        }

        boolean strictDerRequired =
                ScriptVerifyFlags.has(
                        flags,
                        ScriptVerifyFlags.DERSIG
                )
                        ||
                        ScriptVerifyFlags.has(
                                flags,
                                ScriptVerifyFlags.LOW_S
                        )
                        ||
                        ScriptVerifyFlags.has(
                                flags,
                                ScriptVerifyFlags.STRICTENC
                        );

        if (strictDerRequired
                && !isValidDerEncoding(
                signatureWithHashType
        )) {

            throw new ScriptExecutionException(
                    "Non-canonical DER signature"
            );
        }

        if (ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.LOW_S
        )
                && !isLowS(
                signatureWithHashType
        )) {

            throw new ScriptExecutionException(
                    "Non-canonical high-S signature"
            );
        }

        if (ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.STRICTENC
        )
                && !isDefinedHashType(
                signatureWithHashType
        )) {

            throw new ScriptExecutionException(
                    "Undefined signature hash type"
            );
        }
    }

    public static void validatePublicKey(
            byte[] publicKey,
            int flags,
            SignatureVersion signatureVersion
    ) {
        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        if (signatureVersion == null) {
            throw new IllegalArgumentException(
                    "signatureVersion must not be null"
            );
        }

        /*
         * STRICTENC разрешает:
         *
         * compressed:
         * 02/03 + 32 bytes
         *
         * uncompressed:
         * 04 + 64 bytes
         */
        if (ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.STRICTENC
        )
                && !isStrictPublicKeyEncoding(
                publicKey
        )) {

            throw new ScriptExecutionException(
                    "Non-canonical public key encoding"
            );
        }

        /*
         * WITNESS_PUBKEYTYPE применяется только
         * к SegWit v0 script execution.
         *
         * Для legacy этот policy flag ничего
         * дополнительно не запрещает.
         */
        if (signatureVersion
                == SignatureVersion.WITNESS_V0
                && ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.WITNESS_PUBKEYTYPE
        )
                && !isCompressedPublicKeyEncoding(
                publicKey
        )) {

            throw new ScriptExecutionException(
                    "WITNESS_PUBKEYTYPE requires compressed public key"
            );
        }
    }

    public static void validatePublicKey(
            byte[] publicKey,
            int flags
    ) {
        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "publicKey must not be null"
            );
        }

        if (ScriptVerifyFlags.has(
                flags,
                ScriptVerifyFlags.STRICTENC
        )
                && !isStrictPublicKeyEncoding(
                publicKey
        )) {

            throw new ScriptExecutionException(
                    "Non-canonical public key encoding"
            );
        }
    }
}
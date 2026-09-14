package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SignatureStrictEncodingTest {

    @Test
    void definedHashTypesMustBeAccepted() {

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x01)
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x02)
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x03)
                )
        );
    }

    @Test
    void anyoneCanPayHashTypesMustBeAccepted() {

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x81)
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x82)
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        signatureWithHashType(0x83)
                )
        );
    }

    @Test
    void undefinedHashTypesMustBeRejected() {

        int[] invalid = {
                0x00,
                0x04,
                0x80,
                0x84,
                0x41,
                0xff
        };

        for (int hashType : invalid) {

            assertFalse(
                    SignatureEncoding.isDefinedHashType(
                            signatureWithHashType(
                                    hashType
                            )
                    ),
                    "hashType should be rejected: 0x"
                            + Integer.toHexString(hashType)
            );
        }
    }

    @Test
    void compressedPublicKeysMustBeAccepted() {

        byte[] even =
                new byte[33];

        even[0] =
                0x02;

        byte[] odd =
                new byte[33];

        odd[0] =
                0x03;

        assertTrue(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        even
                )
        );

        assertTrue(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        odd
                )
        );
    }

    @Test
    void uncompressedPublicKeyMustBeAccepted() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x04;

        assertTrue(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        publicKey
                )
        );
    }

    @Test
    void hybridPublicKeysMustBeRejected() {

        byte[] first =
                new byte[65];

        first[0] =
                0x06;

        byte[] second =
                new byte[65];

        second[0] =
                0x07;

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        first
                )
        );

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        second
                )
        );
    }

    @Test
    void wrongLengthPublicKeysMustBeRejected() {

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        new byte[32]
                )
        );

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        new byte[34]
                )
        );

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        new byte[64]
                )
        );

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        new byte[66]
                )
        );
    }

    @Test
    void wrongCompressedPrefixMustBeRejected() {

        byte[] publicKey =
                new byte[33];

        publicKey[0] =
                0x04;

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        publicKey
                )
        );
    }

    @Test
    void wrongUncompressedPrefixMustBeRejected() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x02;

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        publicKey
                )
        );
    }

    @Test
    void strictEncMustRejectUndefinedHashType() {

        byte[] signature =
                signatureWithHashType(
                        0x04
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        SignatureEncoding.validateSignature(
                                signature,
                                ScriptVerifyFlags.STRICTENC
                        )
        );
    }

    @Test
    void withoutStrictEncUndefinedHashTypeMustNotBeEncodingError() {

        byte[] signature =
                signatureWithHashType(
                        0x04
                );

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validateSignature(
                                signature,
                                ScriptVerifyFlags.DERSIG
                        )
        );
    }

    @Test
    void strictEncMustRejectInvalidPublicKeyEncoding() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x06;

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.STRICTENC
                        )
        );
    }

    @Test
    void withoutStrictEncInvalidPublicKeyEncodingMustNotBeEncodingError() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x06;

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.NONE
                        )
        );
    }

    @Test
    void emptySignatureMustBypassStrictEncodingChecks() {

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validateSignature(
                                new byte[0],
                                ScriptVerifyFlags.STRICTENC
                                        | ScriptVerifyFlags.DERSIG
                                        | ScriptVerifyFlags.LOW_S
                        )
        );
    }

    /*
     * Минимальная корректная DER transaction signature:
     *
     * 30 06
     * 02 01 01
     * 02 01 01
     * XX
     *
     * XX = sighash type.
     */
    private static byte[] signatureWithHashType(
            int hashType
    ) {

        byte[] signature = {
                0x30,
                0x06,
                0x02,
                0x01,
                0x01,
                0x02,
                0x01,
                0x01,
                0x00
        };

        signature[
                signature.length - 1
                ] =
                (byte) hashType;

        return Arrays.copyOf(
                signature,
                signature.length
        );
    }
}
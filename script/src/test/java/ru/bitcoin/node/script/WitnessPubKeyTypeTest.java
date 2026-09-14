package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessPubKeyTypeTest {

    @Test
    void compressedPublicKeysMustBeRecognized() {

        byte[] even =
                new byte[33];

        even[0] =
                0x02;

        byte[] odd =
                new byte[33];

        odd[0] =
                0x03;

        assertTrue(
                SignatureEncoding.isCompressedPublicKeyEncoding(
                        even
                )
        );

        assertTrue(
                SignatureEncoding.isCompressedPublicKeyEncoding(
                        odd
                )
        );
    }

    @Test
    void uncompressedPublicKeyMustNotBeCompressedEncoding() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x04;

        assertFalse(
                SignatureEncoding.isCompressedPublicKeyEncoding(
                        publicKey
                )
        );
    }

    @Test
    void witnessV0MustRejectUncompressedKeyWhenFlagIsEnabled() {

        byte[] publicKey =
                uncompressedPublicKey();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.WITNESS_PUBKEYTYPE,
                                SignatureVersion.WITNESS_V0
                        )
        );
    }

    @Test
    void witnessV0MustAcceptCompressedKeyWhenFlagIsEnabled() {

        byte[] publicKey =
                compressedPublicKey();

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.WITNESS_PUBKEYTYPE,
                                SignatureVersion.WITNESS_V0
                        )
        );
    }

    @Test
    void legacyMustStillAllowUncompressedKeyWithWitnessPubKeyTypeFlag() {

        byte[] publicKey =
                uncompressedPublicKey();

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.WITNESS_PUBKEYTYPE,
                                SignatureVersion.LEGACY
                        )
        );
    }

    @Test
    void strictEncMustStillAllowUncompressedLegacyKey() {

        byte[] publicKey =
                uncompressedPublicKey();

        assertDoesNotThrow(
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.STRICTENC
                                        | ScriptVerifyFlags.WITNESS_PUBKEYTYPE,
                                SignatureVersion.LEGACY
                        )
        );
    }

    @Test
    void witnessV0WithStrictEncAndWitnessPubKeyTypeMustRejectUncompressedKey() {

        byte[] publicKey =
                uncompressedPublicKey();

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        SignatureEncoding.validatePublicKey(
                                publicKey,
                                ScriptVerifyFlags.STRICTENC
                                        | ScriptVerifyFlags.WITNESS_PUBKEYTYPE,
                                SignatureVersion.WITNESS_V0
                        )
        );
    }

    private static byte[] compressedPublicKey() {

        byte[] publicKey =
                new byte[33];

        publicKey[0] =
                0x02;

        return publicKey;
    }

    private static byte[] uncompressedPublicKey() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x04;

        return publicKey;
    }
}
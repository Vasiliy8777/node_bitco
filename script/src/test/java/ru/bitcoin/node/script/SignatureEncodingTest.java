package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SignatureEncodingTest {

    @Test
    void validDerTransactionSignatureShouldPass() {

        EcdsaSignature signature =
                new EcdsaSignature(
                        BigInteger.ONE,
                        BigInteger.TWO
                );

        byte[] transactionSignature =
                withHashType(
                        signature.toDer(),
                        SignatureHashType.SIGHASH_ALL
                );

        assertTrue(
                SignatureEncoding.isValidDerEncoding(
                        transactionSignature
                )
        );
    }

    @Test
    void signatureWithoutHashTypeShouldFail() {

        EcdsaSignature signature =
                new EcdsaSignature(
                        BigInteger.ONE,
                        BigInteger.TWO
                );

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature.toDer()
                )
        );
    }

    @Test
    void wrongSequenceTagShouldFail() {

        byte[] signature =
                validSignature();

        signature[0] =
                0x31;

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void wrongSequenceLengthShouldFail() {

        byte[] signature =
                validSignature();

        signature[1] =
                0x01;

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void zeroLengthRShouldFail() {

        /*
         * 30 05
         * 02 00
         * 02 01 01
         * 01
         */
        byte[] signature =
                new byte[]{
                        0x30,
                        0x05,
                        0x02,
                        0x00,
                        0x02,
                        0x01,
                        0x01,
                        0x01
                };

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void negativeRShouldFail() {

        /*
         * R = 0x80 without leading 00:
         * interpreted as negative DER INTEGER.
         */
        byte[] signature =
                new byte[]{
                        0x30,
                        0x06,

                        0x02,
                        0x01,
                        (byte) 0x80,

                        0x02,
                        0x01,
                        0x01,

                        0x01
                };

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void unnecessaryLeadingZeroInRShouldFail() {

        byte[] signature =
                new byte[]{
                        0x30,
                        0x07,

                        0x02,
                        0x02,
                        0x00,
                        0x01,

                        0x02,
                        0x01,
                        0x01,

                        0x01
                };

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void negativeSShouldFail() {

        byte[] signature =
                new byte[]{
                        0x30,
                        0x06,

                        0x02,
                        0x01,
                        0x01,

                        0x02,
                        0x01,
                        (byte) 0x80,

                        0x01
                };

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void unnecessaryLeadingZeroInSShouldFail() {

        byte[] signature =
                new byte[]{
                        0x30,
                        0x07,

                        0x02,
                        0x01,
                        0x01,

                        0x02,
                        0x02,
                        0x00,
                        0x01,

                        0x01
                };

        assertFalse(
                SignatureEncoding.isValidDerEncoding(
                        signature
                )
        );
    }

    @Test
    void definedHashTypesShouldPassStrictEncoding() {

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        withHashType(
                                new EcdsaSignature(
                                        BigInteger.ONE,
                                        BigInteger.TWO
                                ).toDer(),
                                SignatureHashType.SIGHASH_ALL
                        )
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        withHashType(
                                new EcdsaSignature(
                                        BigInteger.ONE,
                                        BigInteger.TWO
                                ).toDer(),
                                SignatureHashType.SIGHASH_NONE
                                        |
                                        SignatureHashType.SIGHASH_ANYONECANPAY
                        )
                )
        );

        assertTrue(
                SignatureEncoding.isDefinedHashType(
                        withHashType(
                                new EcdsaSignature(
                                        BigInteger.ONE,
                                        BigInteger.TWO
                                ).toDer(),
                                SignatureHashType.SIGHASH_SINGLE
                        )
                )
        );
    }

    @Test
    void unknownHashTypeShouldFailStrictEncoding() {

        byte[] signature =
                withHashType(
                        new EcdsaSignature(
                                BigInteger.ONE,
                                BigInteger.TWO
                        ).toDer(),
                        0x04
                );

        assertFalse(
                SignatureEncoding.isDefinedHashType(
                        signature
                )
        );
    }

    @Test
    void lowSShouldPass() {

        EcdsaSignature signature =
                new EcdsaSignature(
                        BigInteger.ONE,
                        BigInteger.ONE
                );

        assertTrue(
                SignatureEncoding.isLowS(
                        withHashType(
                                signature.toDer(),
                                SignatureHashType.SIGHASH_ALL
                        )
                )
        );
    }

    @Test
    void highSShouldFailLowSCheck() {

        BigInteger highS =
                Secp256k1.HALF_N
                        .add(
                                BigInteger.ONE
                        );

        EcdsaSignature signature =
                new EcdsaSignature(
                        BigInteger.ONE,
                        highS
                );

        assertFalse(
                SignatureEncoding.isLowS(
                        withHashType(
                                signature.toDer(),
                                SignatureHashType.SIGHASH_ALL
                        )
                )
        );
    }

    @Test
    void compressedPublicKeysShouldBeStrictlyEncoded() {

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
    void uncompressedPublicKeyShouldBeStrictlyEncoded() {

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
    void hybridPublicKeyMustFailStrictEncoding() {

        byte[] publicKey =
                new byte[65];

        publicKey[0] =
                0x06;

        assertFalse(
                SignatureEncoding.isStrictPublicKeyEncoding(
                        publicKey
                )
        );
    }

    @Test
    void emptySignatureMustNotThrowUnderDerSig() {

        assertDoesNotThrow(
                () -> SignatureEncoding.validateSignature(
                        new byte[0],
                        ScriptVerifyFlags.DERSIG
                )
        );
    }

    @Test
    void malformedSignatureMustThrowWhenDerSigEnabled() {

        assertThrows(
                ScriptExecutionException.class,
                () -> SignatureEncoding.validateSignature(
                        new byte[]{
                                0x01,
                                0x02,
                                0x03
                        },
                        ScriptVerifyFlags.DERSIG
                )
        );
    }

    @Test
    void malformedSignatureMayPassEncodingStageWithNoFlags() {

        assertDoesNotThrow(
                () -> SignatureEncoding.validateSignature(
                        new byte[]{
                                0x01,
                                0x02,
                                0x03
                        },
                        ScriptVerifyFlags.NONE
                )
        );
    }

    private static byte[] validSignature() {

        EcdsaSignature signature =
                new EcdsaSignature(
                        BigInteger.ONE,
                        BigInteger.TWO
                );

        return withHashType(
                signature.toDer(),
                SignatureHashType.SIGHASH_ALL
        );
    }

    private static byte[] withHashType(
            byte[] der,
            int hashType
    ) {
        byte[] result =
                Arrays.copyOf(
                        der,
                        der.length + 1
                );

        result[result.length - 1] =
                (byte) hashType;

        return result;
    }
}
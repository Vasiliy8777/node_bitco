package ru.bitcoin.node.cryoto.secp256k1;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EcdsaTest {

    @Test
    void shouldSignAndVerifyDigest() {

        PrivateKey privateKey =
                new PrivateKey(BigInteger.ONE);

        PublicKey publicKey =
                Secp256k1.publicKey(privateKey);

        byte[] digest =
                Sha256.hash(
                        "bitcoin-node"
                                .getBytes(StandardCharsets.UTF_8)
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        assertTrue(
                Secp256k1.verify(
                        digest,
                        signature,
                        publicKey
                )
        );
    }
    //проверка неправильное сообщение
    @Test
    void shouldRejectDifferentDigest() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.valueOf(123456)
                );

        PublicKey publicKey =
                Secp256k1.publicKey(privateKey);

        byte[] digest1 =
                Sha256.hash(
                        "message-1"
                                .getBytes(StandardCharsets.UTF_8)
                );

        byte[] digest2 =
                Sha256.hash(
                        "message-2"
                                .getBytes(StandardCharsets.UTF_8)
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest1,
                        privateKey
                );

        assertFalse(
                Secp256k1.verify(
                        digest2,
                        signature,
                        publicKey
                )
        );
    }
    //проверка deterministic nonce
    @Test
    void signaturesShouldBeDeterministic() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.valueOf(123456)
                );

        byte[] digest =
                Sha256.hash(
                        "same-message"
                                .getBytes(StandardCharsets.UTF_8)
                );

        EcdsaSignature first =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        EcdsaSignature second =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        assertEquals(first, second);
    }
    //проверка Low-S
    @Test
    void generatedSignatureShouldUseLowS() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.valueOf(123456)
                );

        byte[] digest =
                Sha256.hash(
                        "bitcoin"
                                .getBytes(StandardCharsets.UTF_8)
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        assertTrue(signature.isLowS());

        assertTrue(
                signature.s()
                        .compareTo(Secp256k1.HALF_N)
                        <= 0
        );
    }
    //Проверка DER
    @Test
    void shouldEncodeAndDecodeDer() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.valueOf(987654321)
                );

        byte[] digest =
                Sha256.hash(
                        "DER test"
                                .getBytes(StandardCharsets.UTF_8)
                );

        EcdsaSignature original =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        byte[] der =
                original.toDer();

        EcdsaSignature decoded =
                EcdsaSignature.fromDer(der);

        assertEquals(original, decoded);
    }
}

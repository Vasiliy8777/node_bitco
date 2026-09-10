package ru.bitcoin.node.cryoto.hash;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Hash160Test {

    @Test
    void shouldCalculateHash160() {
        byte[] result = Hash160.hash(
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(
                "b6a9c8c230722b7c748331a8b450f05566dc7d0f",
                HexUtils.encode(result)
        );
    }
    @Test
    void shouldCalculateKnownCompressedPublicKeyHash() {

        PrivateKey privateKey =
                new PrivateKey(BigInteger.ONE);

        PublicKey publicKey =
                Secp256k1.publicKey(privateKey);

        byte[] hash160 =
                ru.bitcoin.node.crypto.hash.Hash160.hash(
                        publicKey.compressed()
                );

        assertEquals(
                "751e76e8199196d454941c45d1b3a323f1433bd6",
                HexUtils.encode(hash160)
        );
    }
}
package ru.bitcoin.node.crypto.secp256k1;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Secp256k1Test {

    @Test
    void privateKeyOneShouldProduceGeneratorPoint() {

        PrivateKey privateKey =
                new PrivateKey(BigInteger.ONE);

        PublicKey publicKey =
                Secp256k1.publicKey(privateKey);

        assertEquals(
                "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                HexUtils.encode(publicKey.compressed())
        );
    }
}

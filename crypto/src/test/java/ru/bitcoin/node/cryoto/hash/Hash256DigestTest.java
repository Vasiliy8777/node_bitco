package ru.bitcoin.node.cryoto.hash;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.Hash256Digest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Hash256DigestTest {

    @Test
    void shouldCalculateDoubleSha256() {
        Hash256 hash = Hash256Digest.hash(
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(
                "9595c9df90075148eb06860365df3358" +
                        "4b75bff782a510c6cd4883a419833d50",
                hash.toHex()
        );
    }
}

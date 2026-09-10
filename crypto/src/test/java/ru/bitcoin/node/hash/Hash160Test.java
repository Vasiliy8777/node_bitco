package ru.bitcoin.node.hash;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.crypto.hash.Hash160;

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
}
package ru.bitcoin.node.hash;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.crypto.hash.Ripemd160;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ripemd160Test {

    @Test
    void shouldHashEmptyData() {
        byte[] hash =
                Ripemd160.hash(new byte[0]);

        assertEquals(
                "9c1185a5c5e9fc54612808977ee8f548b2258d31",
                HexUtils.encode(hash)
        );
    }

    @Test
    void shouldHashAbc() {
        byte[] hash =
                Ripemd160.hash(
                        "abc".getBytes(StandardCharsets.UTF_8)
                );

        assertEquals(
                "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
                HexUtils.encode(hash)
        );
    }
}
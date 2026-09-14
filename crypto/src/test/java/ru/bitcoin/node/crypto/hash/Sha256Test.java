package ru.bitcoin.node.crypto.hash;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256Test {

    @Test
    void shouldHashEmptyData() {
        byte[] hash = Sha256.hash(new byte[0]);

        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb924" +
                        "27ae41e4649b934ca495991b7852b855",
                HexUtils.encode(hash)
        );
    }

    @Test
    void shouldHashAbc() {
        byte[] hash =
                Sha256.hash("abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223" +
                        "b00361a396177a9cb410ff61f20015ad",
                HexUtils.encode(hash)
        );
    }
}
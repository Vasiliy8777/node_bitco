package ru.bitcoin.node.crypto.transport;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HkdfSha256Test {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void matchesRfc5869Sha256CaseOne() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertArrayEquals(HEX.parseHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), HkdfSha256.derive(ikm, salt, info, 42));
    }
}

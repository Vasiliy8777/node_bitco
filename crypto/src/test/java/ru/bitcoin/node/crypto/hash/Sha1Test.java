package ru.bitcoin.node.crypto.hash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Sha1Test {

    @Test
    void mustHashEmptyInput() {

        assertArrayEquals(
                hex(
                        "da39a3ee5e6b4b0d3255bfef95601890afd80709"
                ),
                Sha1.hash(
                        new byte[0]
                )
        );
    }

    @Test
    void mustHashAbc() {

        assertArrayEquals(
                hex(
                        "a9993e364706816aba3e25717850c26c9cd0d89d"
                ),
                Sha1.hash(
                        "abc".getBytes(
                                StandardCharsets.US_ASCII
                        )
                )
        );
    }

    @Test
    void nullInputMustFail() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Sha1.hash(
                                null
                        )
        );
    }

    private static byte[] hex(
            String value
    ) {
        byte[] result =
                new byte[
                        value.length() / 2
                        ];

        for (int i = 0;
             i < result.length;
             i++) {

            result[i] =
                    (byte) Integer.parseInt(
                            value.substring(
                                    i * 2,
                                    i * 2 + 2
                            ),
                            16
                    );
        }

        return result;
    }
}
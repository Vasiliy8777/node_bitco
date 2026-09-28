package ru.bitcoin.node.crypto;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class Bip340VectorIntegrityTest {

    private static final String OFFICIAL_SEMANTIC_SHA256 =
            "01c8cabba63b4c9b2f44c975902990086a4fe56eee9d265b187d1e2c1d98ccfb";

    @Test
    void officialBip340VectorsAreSemanticallyPinned() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/bip340/test-vectors.csv")) {
            assertNotNull(in, "Missing /bip340/test-vectors.csv");

            byte[] raw = in.readAllBytes();
            String normalized = new String(raw, StandardCharsets.UTF_8)
                    .replace("\uFEFF", "")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');

            while (normalized.endsWith("\n")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            normalized += "\n";

            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(normalized.getBytes(StandardCharsets.UTF_8))
            );

            assertEquals(
                    OFFICIAL_SEMANTIC_SHA256,
                    actual,
                    "BIP340 vector content changed; line-ending/BOM differences are ignored"
            );
        }
    }
}

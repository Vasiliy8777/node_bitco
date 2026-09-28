package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusCorpusIntegrityTest {

    @Test
    void projectedCoreConsensusCorpusIsSemanticallyPinned() throws Exception {
        assertNormalizedTextSha256(
                "/bitcoin-core-v30/script-all.txt",
                "08dd5a7c3afda29b4c652aac81b8f110155a1bb1a839bf03b5e7cf24cb4f1901"
        );
        assertNormalizedTextSha256(
                "/bitcoin-core-v30/tx_valid.txt",
                "fbf6783ff9824690fb0edd91c929ffb87dc4aaeb2282251d696d99a68ec77027"
        );
        assertNormalizedTextSha256(
                "/bitcoin-core-v30/tx_invalid.txt",
                "91a669b7da658e99d4ed9fc0ee61bdffbf7fd888b105bc49f0ce72efd2c29a70"
        );
    }

    private void assertNormalizedTextSha256(String resource, String expected) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing consensus corpus resource " + resource);

            String normalized = new String(in.readAllBytes(), StandardCharsets.UTF_8)
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
                    expected,
                    actual,
                    "Consensus corpus semantic content changed without an explicit upstream re-pin: " + resource
            );
        }
    }
}

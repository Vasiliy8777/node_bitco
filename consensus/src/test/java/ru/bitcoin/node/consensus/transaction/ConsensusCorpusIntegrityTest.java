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
                "/bitcoin-core-v32/script-all.txt",
                "d1166063f4ad9de3f98a9249d2dd602c36697110b466a57bd46aabeaf8027d5a"
        );
        assertNormalizedTextSha256(
                "/bitcoin-core-v32/tx_valid.txt",
                "58baed3b8123a926510c804c8dba0aa5376121681153b401ba11c6541c75aaad"
        );
        assertNormalizedTextSha256(
                "/bitcoin-core-v32/tx_invalid.txt",
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

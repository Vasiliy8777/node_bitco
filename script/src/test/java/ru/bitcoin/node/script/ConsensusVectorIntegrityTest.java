package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusVectorIntegrityTest {

    @Test
    void coreSighashProjectionIsSemanticallyPinned() throws Exception {
        assertNormalizedTextSha256(
                "/bitcoin-core-v30/sighash-vectors.txt",
                "03dd34083423e0c6a96431f0a7628a21ffbd2271e78ad8f3926f32e9ddbe0819"
        );
    }

    @Test
    void bip341KeyPathProjectionIsSemanticallyPinned() throws Exception {
        assertNormalizedTextSha256(
                "/bip341/key-path-vectors.txt",
                "825809566b961708f4eff318568bd22d87f494bf2a697c030e6a354ca79618cf"
        );

        // Canonical upstream JSON is deliberately kept byte-exact.
        assertByteSha256(
                "/bip341/wallet-test-vectors.json",
                "403e19fb81dd1f31e745699216308f61fb403774b2aafa87b631b8f7c042d37f"
        );
    }

    private void assertNormalizedTextSha256(String resource, String expected) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing vector resource " + resource);

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
                    "Vector semantic content changed without explicit upstream re-pin: " + resource
            );
        }
    }

    private void assertByteSha256(String resource, String expected) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "Missing vector resource " + resource);
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())
            );
            assertEquals(
                    expected,
                    actual,
                    "Vector bytes changed without explicit upstream re-pin: " + resource
            );
        }
    }
}

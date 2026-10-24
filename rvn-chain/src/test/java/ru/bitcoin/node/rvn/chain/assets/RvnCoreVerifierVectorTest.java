package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RvnCoreVerifierVectorTest {
    @Test void matchesExecutedPinnedCoreExpressionOracle() throws Exception {
        int count = 0;
        try (var input = getClass().getResourceAsStream("/core-reference/verifier-vectors.tsv")) {
            assertNotNull(input);
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\t");
                String expression = fields[0].equals("-") ? "" : new String(HexFormat.of().parseHex(fields[0]), StandardCharsets.US_ASCII);
                int mask = Integer.parseInt(fields[1]); boolean valid = Boolean.parseBoolean(fields[2]);
                if (!valid) assertThrows(SecurityException.class, () -> RvnRestrictedVerifier.fromOutput(expression), line);
                else assertEquals(Boolean.parseBoolean(fields[3]), RvnRestrictedVerifier.fromOutput(expression).evaluate(q ->
                        (mask & switch(q) {
                            case "#AAA" -> 1; case "#BBB" -> 2; case "#CCC" -> 4;
                            default -> throw new AssertionError("Unknown fixture qualifier");
                        }) != 0), line);
                count++;
            }
        }
        assertEquals(280, count);
    }
}

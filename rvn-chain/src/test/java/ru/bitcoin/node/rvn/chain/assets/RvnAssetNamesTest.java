package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnAssetNamesTest {
    @Test
    void literalAssertionsFromPinnedCoreNameSuite() throws Exception {
        int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/core-reference/asset-names.tsv")), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                var fields = line.split("\t", -1);
                assertEquals(2, fields.length);
                assertEquals(Boolean.parseBoolean(fields[0]), RvnAssetNames.classify(fields[1]).isPresent(), fields[1]);
                count++;
            }
        }
        assertTrue(count >= 50);
    }

    @Test
    void classifiesFamiliesAndEnforcesBoundaryLengths() {
        assertEquals(RvnAssetNames.Type.ROOT, RvnAssetNames.requireValid("ASSET"));
        assertEquals(RvnAssetNames.Type.SUB, RvnAssetNames.requireValid("ASSET/SUB"));
        assertEquals(RvnAssetNames.Type.UNIQUE, RvnAssetNames.requireValid("ASSET#serial-1"));
        assertEquals(RvnAssetNames.Type.CHANNEL, RvnAssetNames.requireValid("ASSET~news"));
        assertEquals(RvnAssetNames.Type.OWNER, RvnAssetNames.requireValid("ASSET!"));
        assertEquals(RvnAssetNames.Type.VOTE, RvnAssetNames.requireValid("ASSET^VOTE"));
        assertEquals(RvnAssetNames.Type.QUALIFIER, RvnAssetNames.requireValid("#QUALIFIER"));
        assertEquals(RvnAssetNames.Type.SUB_QUALIFIER, RvnAssetNames.requireValid("#QUALIFIER/#SUB"));
        assertEquals(RvnAssetNames.Type.RESTRICTED, RvnAssetNames.requireValid("$RESTRICTED"));
        assertDoesNotThrow(() -> RvnAssetNames.requireValid("A".repeat(30)));
        assertThrows(IllegalArgumentException.class, () -> RvnAssetNames.requireValid("A".repeat(31)));
        assertDoesNotThrow(() -> RvnAssetNames.requireValid("A".repeat(30) + "!"));
        for (String invalid : List.of("", "RVN", "#RVN", "ABC//SUB", "ABC/", "#ABC/#SUB/#NEXT", "ABC~news_", "ABC#Ünicode"))
            assertThrows(IllegalArgumentException.class, () -> RvnAssetNames.requireValid(invalid), invalid);
        assertTrue(RvnAssetNames.classify(null).isEmpty());
        assertTrue(RvnAssetNames.classify("A".repeat(1_000_000)).isEmpty());
    }
}

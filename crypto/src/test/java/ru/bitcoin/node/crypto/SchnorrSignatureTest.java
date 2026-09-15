package ru.bitcoin.node.crypto;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.bitcoin.node.common.bytes.HexUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchnorrSignatureTest {
    @TestFactory
    List<DynamicTest> officialBip340Vectors() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/bip340/test-vectors.csv"), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).map(line -> {
                String[] fields = line.split(",", -1);
                return DynamicTest.dynamicTest("BIP340 vector " + fields[0], () ->
                        assertEquals(Boolean.parseBoolean(fields[6]), SchnorrSignature.verify(
                                HexUtils.decode(fields[4]), HexUtils.decode(fields[2]), HexUtils.decode(fields[5]))));
            }).toList();
        }
    }
}

package ru.bitcoin.node.script;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreSighashVectorsTest {
    @TestFactory Stream<DynamicTest> completeCoreV30SighashCorpus() throws IOException {
        List<String> lines;
        try(var input=getClass().getResourceAsStream("/bitcoin-core-v30/sighash-vectors.txt")) {
            assertNotNull(input);
            lines=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8)).lines().toList();
        }
        assertEquals(500,lines.size());
        return java.util.stream.IntStream.range(0,lines.size()).mapToObj(i -> DynamicTest.dynamicTest("Core sighash #"+i, () -> {
            var fields=lines.get(i).split("\\|",-1);
            var hex=HexFormat.of();
            var tx=TransactionParser.parse(hex.parseHex(fields[0]));
            var digest=LegacySignatureHash.calculate(tx,Integer.parseInt(fields[2]),hex.parseHex(fields[1]),Integer.parseInt(fields[3]));
            assertEquals(fields[4],new Hash256(digest).toDisplayHex());
        }));
    }
}

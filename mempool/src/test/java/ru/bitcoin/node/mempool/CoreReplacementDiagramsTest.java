package ru.bitcoin.node.mempool;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CoreReplacementDiagramsTest {
    @TestFactory Stream<DynamicTest> nativeMainStagingDiagrams() throws Exception {
        var operations=TxGraphReferenceModel.scenarios(lines("diagram-operations.txt"));
        var answers=TxGraphReferenceModel.answers(lines("diagram-results.tsv"));
        return java.util.stream.IntStream.range(0,operations.size()).mapToObj(i ->
                DynamicTest.dynamicTest("Core main/staging diagram "+i,()->TxGraphReferenceModel.replay(operations.get(i),answers)));
    }
    private List<String> lines(String file) throws Exception {
        try(var input=getClass().getResourceAsStream("/core32-reference/"+file)) {
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
}

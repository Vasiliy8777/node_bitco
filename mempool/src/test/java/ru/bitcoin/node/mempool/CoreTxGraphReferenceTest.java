package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreTxGraphReferenceTest {
    @TestFactory List<DynamicTest> mainStagingCommitAbortFeesAndRemovalsMatchCore() throws Exception {
        var scenarios = TxGraphReferenceModel.scenarios(resource("txgraph-operations.txt"));
        var answers = TxGraphReferenceModel.answers(resource("txgraph-snapshots.tsv"));
        assertEquals(24,scenarios.size()); assertEquals(3600,answers.size());
        var result = new ArrayList<DynamicTest>();
        for (int i=0;i<scenarios.size();i++) {
            var operations = scenarios.get(i);
            result.add(DynamicTest.dynamicTest("Core TxGraph trace "+i,()->TxGraphReferenceModel.replay(operations,answers)));
        }
        return result;
    }
    private static List<String> resource(String name) throws Exception {
        try (var stream = CoreTxGraphReferenceTest.class.getResourceAsStream("/core32-reference/"+name)) {
            assertNotNull(stream,name); return new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
}

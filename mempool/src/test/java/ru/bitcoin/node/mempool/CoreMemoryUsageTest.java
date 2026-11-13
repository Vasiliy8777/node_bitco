package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CoreMemoryUsageTest {
    @TestFactory Stream<DynamicTest> nativeGraphMemorySnapshots() throws Exception {
        var operations=TxGraphReferenceModel.scenarios(lines("txgraph-operations.txt"));
        var answers=TxGraphReferenceModel.answers(lines("txgraph-memory.tsv"));
        return java.util.stream.IntStream.range(0,operations.size()).mapToObj(i ->
                DynamicTest.dynamicTest("Core memory/staging scenario "+i,()->TxGraphReferenceModel.replay(operations.get(i),answers)));
    }
    @Test void allocatorRoundingMatchesCoreBoundaries() {
        assertEquals(0,CoreMemoryUsage.allocation(0));
        assertEquals(32,CoreMemoryUsage.allocation(1));
        assertEquals(32,CoreMemoryUsage.allocation(16));
        assertEquals(48,CoreMemoryUsage.allocation(17));
        assertEquals(64,CoreMemoryUsage.allocation(40));
        assertThrows(IllegalArgumentException.class,()->CoreMemoryUsage.allocation(-1));
        assertThrows(ArithmeticException.class,()->CoreMemoryUsage.allocation(Long.MAX_VALUE));
    }
    @Test void randomizedArrayRetainsAndShrinksItsHistoricalCapacity() {
        assertEquals(8,CoreMemoryUsage.randomizedCapacity(0,0,0,5));
        assertEquals(8,CoreMemoryUsage.randomizedCapacity(8,5,1,0));
        assertEquals(3,CoreMemoryUsage.randomizedCapacity(8,5,2,0));
        assertEquals(6,CoreMemoryUsage.randomizedCapacity(3,3,0,1));
        assertEquals(1,CoreMemoryUsage.randomizedCapacity(8,5,5,0));
        assertEquals(8,CoreMemoryUsage.randomizedCapacity(8,1,1,0));
        assertThrows(IllegalArgumentException.class,()->CoreMemoryUsage.randomizedCapacity(0,1,0,0));
    }
    private List<String> lines(String file) throws Exception {
        try (var input=getClass().getResourceAsStream("/core32-reference/"+file)) {
            assertNotNull(input); return new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpanningForestLinearizerTest {
    @TestFactory List<DynamicTest> matchesNativeCoreBudgetContinuationAndPostlinearization() throws Exception {
        var graphs = new HashMap<Integer, SpanningForestLinearizer.Graph>();
        for (var row : resource("cluster-vectors.tsv")) {
            var fields = row.split("\t", -1); var vertices = fields[1].isEmpty() ? new String[0] : fields[1].split(";");
            var fees = new long[vertices.length]; var weights = new long[vertices.length]; var parents = new BitSet[vertices.length];
            for (int i = 0; i < vertices.length; i++) {
                var vertex = vertices[i].split(":", -1); var rate = vertex[0].split(",");
                fees[i] = Long.parseLong(rate[0]); weights[i] = Long.parseLong(rate[1]); parents[i] = new BitSet();
                if (!vertex[1].isEmpty()) for (var parent : vertex[1].split(",")) parents[i].set(Integer.parseInt(parent));
            }
            graphs.put(Integer.parseInt(fields[0]), new SpanningForestLinearizer.Graph(fees, weights, parents));
        }
        var rows = resource("budget-vectors.tsv"); assertEquals(9600, rows.size());
        return rows.stream().map(row -> {
            var fields = row.split("\t", -1);
            int id = Integer.parseInt(fields[0]), mode = Integer.parseInt(fields[3]);
            long budget = Long.parseLong(fields[1]), seed = Long.parseLong(fields[2]);
            return DynamicTest.dynamicTest("Core SFL graph=" + id + " budget=" + budget + " mode=" + mode, () -> {
                var graph = graphs.get(id); var old = new ArrayList<Integer>();
                if (!fields[7].isEmpty()) for (var index : fields[7].split(",")) old.add(Integer.parseInt(index));
                var result = SpanningForestLinearizer.linearize(graph, budget, seed, old, mode != 2, mode != 3);
                assertAll(
                        () -> assertEquals(Long.parseLong(fields[4]), result.cost(), "cost"),
                        () -> assertEquals(fields[5].equals("1"), result.optimal(), "optimal"),
                        () -> assertEquals(fields[6], result.order().stream().map(Object::toString)
                                .collect(java.util.stream.Collectors.joining(",")), "order"));
            });
        }).toList();
    }

    @Test void rejectsInvalidGraphsAndOldPermutations() {
        var self = new BitSet(); self.set(0);
        assertThrows(IllegalArgumentException.class, () -> new SpanningForestLinearizer.Graph(new long[]{1}, new long[]{1}, new BitSet[]{self}));
        var graph = new SpanningForestLinearizer.Graph(new long[]{1, 2}, new long[]{1, 1}, new BitSet[]{new BitSet(), new BitSet()});
        assertThrows(IllegalArgumentException.class, () -> SpanningForestLinearizer.linearize(graph, -1, 0, List.of(), true, true));
        assertThrows(IllegalArgumentException.class, () -> SpanningForestLinearizer.linearize(graph, 1000, 0, List.of(0, 0), true, true));
    }
    private static List<String> resource(String name) throws Exception {
        try (var stream = SpanningForestLinearizerTest.class.getResourceAsStream("/core32-reference/" + name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("#")).toList();
        }
    }
}

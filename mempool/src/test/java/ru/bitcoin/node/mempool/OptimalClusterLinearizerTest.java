package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OptimalClusterLinearizerTest {
    @TestFactory List<DynamicTest> matchesCore32OptimalOrderAndMinimalChunks() throws Exception {
        String fixtures;
        try (var stream = getClass().getResourceAsStream("/core32-reference/cluster-vectors.tsv")) {
            assertNotNull(stream);
            fixtures = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = fixtures.lines().filter(line -> !line.startsWith("#")).toList();
        assertEquals(384, rows.size());
        return rows.stream().map(row -> DynamicTest.dynamicTest("Core cluster " + row.split("\t")[0], () -> {
            String[] fields = row.split("\t", -1);
            var entries = new LinkedHashMap<Hash256, MempoolEntry>();
            if (!fields[1].isEmpty()) {
                String[] vertices = fields[1].split(";");
                for (int i = 0; i < vertices.length; i++) {
                    String[] vertex = vertices[i].split(":", -1);
                    String[] rate = vertex[0].split(",");
                    var inputs = new ArrayList<TxIn>();
                    if (!vertex[1].isEmpty()) for (var parent : vertex[1].split(","))
                        inputs.add(new TxIn(new OutPoint(id(Integer.parseInt(parent)), new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE));
                    var transaction = new Transaction(2, inputs, List.of(new TxOut(i, new byte[]{0x51})), new UInt32(0));
                    // Synthetic graph keys let both implementations use the same
                    // explicit fallback order without depending on hash preimages.
                    entries.put(id(i), new MempoolEntry(transaction, Long.parseLong(rate[0]), Long.parseLong(rate[1]), 0));
                }
            }
            // This saved oracle is raw Linearize at unlimited budget, before PostLinearize.
            // Production now uses the Core budgeted path and its postprocessing.
            var order = ClusterOptimization.standalone(entries, entries.keySet(), 1_000_000_000L, 0, false);
            assertEquals(OptimalClusterLinearizer.linearize(entries, entries.keySet()), order,
                    "Independent maximum-closure test oracle");
            assertEquals(fields[2], order.stream().map(OptimalClusterLinearizerTest::index)
                    .map(Object::toString).collect(java.util.stream.Collectors.joining(",")), "linearization");
            var chunks = ClusterLinearization.chunks(entries, order);
            String actual = chunks.stream().map(chunk -> chunk.fee() + "," + chunk.adjustedWeight() + ":"
                    + chunk.transactions().stream().map(OptimalClusterLinearizerTest::index).sorted().map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","))).collect(java.util.stream.Collectors.joining(";"));
            assertEquals(fields[3], actual, "minimal chunks");
        })).toList();
    }

    @Test void sharedParentIsAmortizedAcrossBothPayingChildrenBeforeCompetingPackage() {
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        entries.put(id(0), entry(0, 1000));
        entries.put(id(1), entry(1500, 1000, 0));
        entries.put(id(2), entry(1500, 1000, 0));
        entries.put(id(3), entry(900, 1000));
        var chunks = ClusterLinearization.chunks(entries, entries.keySet());
        assertEquals(List.of(id(0), id(1), id(2)), chunks.getFirst().transactions());
        assertEquals(3000, chunks.getFirst().fee());
        assertEquals(3000, chunks.getFirst().adjustedWeight());
        assertEquals(List.of(id(3)), chunks.getLast().transactions());
    }

    @Test void rejectsCyclesInsteadOfTreatingThemAsAnAtomicPackage() {
        var entries = Map.of(id(0), entry(100, 400, 1), id(1), entry(100, 400, 0));
        assertThrows(IllegalArgumentException.class, () -> ClusterLinearization.linearize(entries, entries.keySet()));
    }

    @Test void extremeCrossProductsRemainExactWithNegativeFeeDeltas() {
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        entries.put(id(0), entry(-1_000_000_000_000_000L, 400));
        entries.put(id(1), entry(2_000_000_000_000_001L, 401, 0));
        entries.put(id(2), entry(1_000_000_000_000_000L, 801));
        var chunks = ClusterLinearization.chunks(entries, entries.keySet());
        assertEquals(List.of(id(0), id(1)), chunks.getFirst().transactions());
        assertEquals(1_000_000_000_000_001L, chunks.getFirst().fee());
    }

    private static MempoolEntry entry(long fee, long weight, int... parents) {
        var inputs = Arrays.stream(parents).mapToObj(parent -> new TxIn(new OutPoint(id(parent), new UInt32(0)),
                new byte[0], TxIn.FINAL_SEQUENCE)).toList();
        return new MempoolEntry(new Transaction(2, inputs, List.of(new TxOut(0, new byte[]{0x51})), new UInt32(0)),
                0, weight, 0, 0, fee);
    }

    private static Hash256 id(int index) { return Hash256.fromDisplayHex(String.format("%064x", index + 1)); }
    private static int index(Hash256 id) { return Integer.parseInt(id.toDisplayHex(), 16) - 1; }
}

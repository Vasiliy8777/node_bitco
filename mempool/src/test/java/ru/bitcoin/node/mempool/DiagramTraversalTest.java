package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagramTraversalTest {
    @Test void comparisonMatchesIndependentPointwiseReference() {
        var random = new Random(32611);
        for (int i = 0; i < 2000; i++) {
            var left = diagram(random);
            var right = diagram(random);
            assertEquals(reference(left, right), ClusterLinearization.compareDiagrams(left, right), "case " + i);
            assertEquals(0, ClusterLinearization.compareDiagrams(left, left));
        }
    }

    @Test void equalLongDiagramsNeedOnlyLinearSegmentReads() {
        var chunks = new ArrayList<ClusterLinearization.Chunk>();
        for (int i = 0; i < 20000; i++) chunks.add(chunk(i, 400 + i % 4));
        var left = new CountingList(chunks);
        var right = new CountingList(chunks);
        assertEquals(0, ClusterLinearization.compareDiagrams(left, right));
        assertTrue(left.reads + right.reads <= 6 * chunks.size(), "Segment reads: " + (left.reads + right.reads));
    }

    @Test void differentLengthsHugeFeesAndCrossingPrefixesRemainExact() {
        var a = List.of(chunk(Long.MAX_VALUE, 3), chunk(Long.MIN_VALUE + 1, 8));
        var b = List.of(chunk(Long.MAX_VALUE - 1, 4), chunk(Long.MIN_VALUE + 3, 2));
        assertEquals(reference(a, b), ClusterLinearization.compareDiagrams(a, b));
        assertEquals(reference(b, a), ClusterLinearization.compareDiagrams(b, a));
        assertEquals(reference(a, List.of()), ClusterLinearization.compareDiagrams(a, List.of()));
        assertEquals(reference(List.of(), a), ClusterLinearization.compareDiagrams(List.of(), a));
    }

    private static List<ClusterLinearization.Chunk> diagram(Random random) {
        var result = new ArrayList<ClusterLinearization.Chunk>();
        for (int i = random.nextInt(12); i > 0; i--) result.add(chunk(random.nextInt(401) - 200, random.nextInt(50) + 1));
        return result;
    }
    private static ClusterLinearization.Chunk chunk(long fee, long weight) {
        return new ClusterLinearization.Chunk(List.of(new Hash256(new byte[32])), fee, Math.ceilDiv(weight, 4), weight);
    }
    private static int reference(List<ClusterLinearization.Chunk> left, List<ClusterLinearization.Chunk> right) {
        var points = new TreeSet<Long>();
        for (var diagram : List.of(left, right)) {
            long x = 0;
            for (var chunk : diagram) { x += chunk.adjustedWeight(); points.add(x); }
        }
        int result = 0;
        for (long x : points) {
            var a = value(left, x); var b = value(right, x);
            int compare = a[0].multiply(b[1]).compareTo(b[0].multiply(a[1]));
            if (compare < 0) return -1;
            if (compare > 0) result = 1;
        }
        return result;
    }
    private static BigInteger[] value(List<ClusterLinearization.Chunk> diagram, long x) {
        var fee = BigInteger.ZERO;
        for (var chunk : diagram) {
            if (x < chunk.adjustedWeight()) return new BigInteger[]{fee.multiply(BigInteger.valueOf(chunk.adjustedWeight()))
                    .add(BigInteger.valueOf(chunk.fee()).multiply(BigInteger.valueOf(x))), BigInteger.valueOf(chunk.adjustedWeight())};
            fee = fee.add(BigInteger.valueOf(chunk.fee())); x -= chunk.adjustedWeight();
        }
        return new BigInteger[]{fee, BigInteger.ONE};
    }
    private static final class CountingList extends AbstractList<ClusterLinearization.Chunk> {
        final List<ClusterLinearization.Chunk> chunks;
        int reads;
        CountingList(List<ClusterLinearization.Chunk> chunks) { this.chunks = chunks; }
        @Override public ClusterLinearization.Chunk get(int index) {
            if (++reads > 3 * chunks.size()) throw new AssertionError("Repeated full-diagram scans");
            return chunks.get(index);
        }
        @Override public int size() { return chunks.size(); }
    }
}

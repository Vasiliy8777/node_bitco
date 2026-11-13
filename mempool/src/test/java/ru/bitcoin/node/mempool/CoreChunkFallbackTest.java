package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreChunkFallbackTest {
    private static Hash256 id(String byteValue) { return Hash256.fromHex(byteValue.repeat(32)); }
    private static ClusterLinearization.Chunk chunk(long fee, long weight, Hash256... ids) {
        return new ClusterLinearization.Chunk(List.of(ids), fee, (weight + 3) / 4, weight);
    }
    private static List<Hash256> merge(List<ClusterLinearization.Chunk>... clusters) {
        return ClusterLinearization.mergeChunks(List.of(clusters)).stream().flatMap(c -> c.transactions().stream()).toList();
    }
    @Test void equalSingletonsUseUnsignedSerializedTxidBytes() {
        var displayFirst = Hash256.fromHex("01" + "00".repeat(31));
        var serializedFirst = Hash256.fromHex("00".repeat(31) + "02");
        assertTrue(displayFirst.toDisplayHex().compareTo(serializedFirst.toDisplayHex()) < 0);
        assertEquals(List.of(serializedFirst, displayFirst), merge(
                List.of(chunk(100, 400, displayFirst)), List.of(chunk(100, 400, serializedFirst))));
    }
    @Test void smallerEqualRatePrefixPrecedesHashFallback() {
        var large = id("01"); var small = id("ff");
        assertEquals(List.of(small, large), merge(List.of(chunk(200, 800, large)), List.of(chunk(100, 400, small))));
    }
    @Test void fallbackUsesMaximumIdOfChunkRatherThanFirstTransaction() {
        var low = id("01"); var high = id("ff"); var middle = id("80");
        assertEquals(List.of(middle, low, high), merge(List.of(chunk(200, 800, low, high)), List.of(chunk(200, 800, middle))));
    }
    @Test void equalRatePrefixAccumulatesAcrossChunksWithoutBreakingDependencies() {
        var parent = id("01"); var child = id("02"); var independent = id("ff");
        assertEquals(List.of(parent, independent, child), merge(
                List.of(chunk(100, 400, parent), chunk(100, 400, child)), List.of(chunk(150, 600, independent))));
    }
    @Test void prefixResetsWhenRateDrops() {
        var highRate = id("02"); var lowRate = id("ff"); var independent = id("01");
        assertEquals(List.of(highRate, lowRate, independent), merge(
                List.of(chunk(200, 400, highRate), chunk(100, 400, lowRate)), List.of(chunk(150, 600, independent))));
    }
    @Test void negativeEqualRatesUseTheSamePrefixTieRule() {
        var large = id("01"); var small = id("ff");
        assertEquals(List.of(small, large), merge(List.of(chunk(-200, 800, large)), List.of(chunk(-100, 400, small))));
    }
    @Test void clusterFallbackAlsoUsesSerializedBytesForEqualRateSiblings() {
        var parent = id("55");
        var displayFirst = Hash256.fromHex("01" + "00".repeat(31));
        var serializedFirst = Hash256.fromHex("00".repeat(31) + "02");
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        entries.put(parent, entry(id("aa"), 1));
        entries.put(displayFirst, entry(parent, 2));
        entries.put(serializedFirst, entry(parent, 3));
        assertEquals(List.of(parent, serializedFirst, displayFirst), ClusterLinearization.linearize(entries, entries.keySet()));
    }
    private static MempoolEntry entry(Hash256 parent, long tag) {
        var tx = new Transaction(2, List.of(new TxIn(new OutPoint(parent, new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
        return new MempoolEntry(tx, 1000, 400, 0);
    }
}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.bitcoin.node.common.types.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class EqualRateEvictionTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void equalSingletonsEvictLargestSerializedTxidRegardlessOfInsertion(boolean reverse) {
        var a = entry(1, 100, 400); var b = entry(2, 100, 400);
        var sorted = new ArrayList<>(List.of(a, b));
        sorted.sort((left, right) -> left.transaction().txId().compareRawBytes(right.transaction().txId()));
        var input = new ArrayList<>(sorted); if (reverse) Collections.reverse(input);
        var entries = map(input);
        assertEquals(1000, MempoolGraphPolicy.trim(entries, 100, null));
        assertEquals(Set.of(sorted.getFirst().transaction().txId()), entries.keySet());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void largestEqualRatePrefixDeterminesRemovalAndRoundedFloor(boolean negative) {
        var small = entry(3, negative ? -401 : 401, 401);
        var large = entry(4, negative ? -402 : 402, 402);
        var entries = map(List.of(small, large));
        assertEquals(negative ? -3981 : 3980, MempoolGraphPolicy.trim(entries, 101, null));
        assertEquals(Set.of(small.transaction().txId()), entries.keySet());
    }
    @Test void equalRatePrefixIncludesEarlierChunksOfTheSameRate() {
        var parent = tx(List.of(FUND), 2, 90000);
        var child = tx(List.of(point(parent, 0)), 2, 80000);
        var independent = entry(5, 150, 600);
        var entries = map(List.of(independent, new MempoolEntry(parent, 100, 400, 0), new MempoolEntry(child, 100, 400, 0)));
        assertEquals(1000, MempoolGraphPolicy.trim(entries, 250, null));
        assertEquals(Set.of(parent.txId(), independent.transaction().txId()), entries.keySet());
    }
    @Test void newlyAddedWorstChunkRejectsWithoutEvictingExistingPeerChunk() {
        var small = entry(6, 401, 401); var large = entry(7, 402, 402);
        var entries = map(List.of(small, large)); var before = new LinkedHashMap<>(entries);
        var failure = assertThrows(MempoolAdmissionException.class,
                () -> MempoolGraphPolicy.trim(entries, 101, large.transaction().txId()));
        assertEquals("mempool-full", failure.getMessage());
        assertEquals(before, entries);
    }
    @Test void equalSizeChunksUseMaximumIdAcrossAllMembers() {
        var parent = tx(List.of(FUND), 2, 90000);
        var child = tx(List.of(point(parent, 0)), 2, 80000);
        var independent = entry(8, 200, 800);
        var family = List.of(new MempoolEntry(parent, 0, 400, 0), new MempoolEntry(child, 200, 400, 0));
        var maximum = parent.txId().compareRawBytes(child.txId()) > 0 ? parent.txId() : child.txId();
        boolean familyIsWorst = maximum.compareRawBytes(independent.transaction().txId()) > 0;
        var input = new ArrayList<MempoolEntry>();
        if (familyIsWorst) { input.add(independent); input.addAll(family); }
        else { input.addAll(family); input.add(independent); }
        var entries = map(input);
        assertEquals(1000, MempoolGraphPolicy.trim(entries, 200, null));
        assertEquals(familyIsWorst ? Set.of(independent.transaction().txId()) : Set.of(parent.txId(), child.txId()), entries.keySet());
    }
    private static MempoolEntry entry(int tag, long fee, long weight) {
        var transaction = tx(List.of(new ru.bitcoin.node.protocol.transaction.OutPoint(
                Hash256.fromDisplayHex(String.format("%064x", tag + 100)), new UInt32(0))), 2, 90000 + tag);
        return new MempoolEntry(transaction, 0, weight, 0, 0, fee);
    }
    private static LinkedHashMap<Hash256, MempoolEntry> map(List<MempoolEntry> entries) {
        var result = new LinkedHashMap<Hash256, MempoolEntry>();
        for (var entry : entries) result.put(entry.transaction().txId(), entry);
        return result;
    }
}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageRbfClusterCountTest {
    @Test void twoConflictingClustersMayEvictOneHundredTwentyTransactions() {
        var funds = funds(2);
        var coins = coins(funds);
        var pool = new Mempool();
        var originals = new ArrayList<Transaction>();
        for (var fund : funds) {
            OutPoint previous = fund;
            long value = 100000;
            for (int i = 0; i < 60; i++) {
                value -= 100;
                var transaction = tx(List.of(previous), 2, value);
                pool.admit(transaction, CONTEXT, coins);
                originals.add(transaction);
                previous = point(transaction, 0);
            }
        }
        assertEquals(120, pool.size());
        var parent = tx(funds, 2, 200000);
        var child = tx(List.of(point(parent, 0)), 2, 100000);
        assertDoesNotThrow(() -> pool.admitPackage(List.of(parent, child), CONTEXT, coins));
        assertEquals(Set.of(parent.txId(), child.txId()), ids(pool));
        originals.forEach(original -> assertFalse(pool.contains(original.txId())));
    }

    @Test void oneHundredDistinctConflictingClustersAreAllowed() {
        var funds = funds(100);
        var coins = coins(funds);
        var pool = roots(funds, coins);
        var parent = tx(funds, 2, 10000000);
        var child = tx(List.of(point(parent, 0)), 2, 1000000);
        assertDoesNotThrow(() -> pool.admitPackage(List.of(parent, child), CONTEXT, coins));
        assertEquals(Set.of(parent.txId(), child.txId()), ids(pool));
    }

    @Test void oneHundredOneDistinctClustersAreRejectedWithoutMutation() {
        var funds = funds(101);
        var coins = coins(funds);
        var pool = roots(funds, coins);
        var before = ids(pool);
        long sequence = pool.snapshot().sequence();
        var parent = tx(funds, 2, 10100000);
        var child = tx(List.of(point(parent, 0)), 2, 1000000);
        var error = assertThrows(MempoolAdmissionException.class,
                () -> pool.admitPackage(List.of(parent, child), CONTEXT, coins));
        assertEquals("package-rbf-too-many-conflicts", error.getMessage());
        assertEquals(before, ids(pool));
        assertEquals(sequence, pool.snapshot().sequence());
    }

    private static Mempool roots(List<OutPoint> funds, UtxoView coins) {
        var pool = new Mempool();
        for (var fund : funds) pool.admit(tx(List.of(fund), 2, 99900), CONTEXT, coins);
        return pool;
    }
    private static List<OutPoint> funds(int count) {
        var result = new ArrayList<OutPoint>();
        for (int i = 1; i <= count; i++) result.add(new OutPoint(Hash256.fromDisplayHex(String.format("%064x", i)), new UInt32(0)));
        return result;
    }
    private static UtxoView coins(List<OutPoint> funds) {
        var known = new HashSet<>(funds);
        return point -> known.contains(point) ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : Optional.empty();
    }
    private static Set<Hash256> ids(Mempool pool) {
        var result = new HashSet<Hash256>();
        pool.entries().forEach(entry -> result.add(entry.transaction().txId()));
        return result;
    }
}

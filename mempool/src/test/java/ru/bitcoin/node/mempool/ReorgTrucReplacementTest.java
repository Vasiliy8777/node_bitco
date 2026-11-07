package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class ReorgTrucReplacementTest {
    @Test void indirectRemovalThroughAnotherParentDoesNotGrantDirectConflictException() {
        var otherFund = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
        UtxoView coins = point -> point.equals(otherFund)
                ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(point);
        var parent = tx(List.of(FUND), 3, 22000, 22000, 22000, 22000);
        var otherParent = tx(List.of(otherFund), 3, 20000, 20000, 20000);
        UtxoView confirmed = point -> {
            if (point.transactionId().equals(parent.txId()) && point.outputIndex().value() < 4)
                return Optional.of(new UtxoEntry(22000, SCRIPT, 100, false));
            if (point.transactionId().equals(otherParent.txId()) && point.outputIndex().value() < 3)
                return Optional.of(new UtxoEntry(20000, SCRIPT, 100, false));
            return Optional.empty();
        };
        var pool = new Mempool();
        var children = new ArrayList<Transaction>();
        for (int i = 0; i < 3; i++) {
            var child = tx(List.of(point(parent, i), point(otherParent, i)), 3, 37000);
            pool.admit(child, CONTEXT, confirmed);
            children.add(child);
        }
        pool.reconcile(CONTEXT, coins, Set.of(), List.of(parent, otherParent));
        assertEquals(5, pool.size());
        // Replacing otherParent also removes all three children. The final
        // family would have just two members, but the direct conflict is not
        // a descendant of parent, so Core still rejects the new child.
        var replacement = tx(List.of(point(parent, 3), otherFund), 3, 10000);
        var error = assertThrows(MempoolAdmissionException.class, () -> pool.admit(replacement, CONTEXT, coins));
        assertEquals("truc-topology", error.getMessage());
        assertTrue(pool.contains(otherParent.txId()));
        children.forEach(child -> assertTrue(pool.contains(child.txId())));
        assertEquals(5, pool.size());
    }

    @Test void directConflictDoesNotWaiveVersionInheritance() {
        var f = fixture();
        var wrongVersion = tx(List.of(point(f.parent(), 0)), 2, 10000);
        var error = assertThrows(MempoolAdmissionException.class, () -> f.pool().admit(wrongVersion, CONTEXT, COINS));
        assertEquals("truc-version-inheritance", error.getMessage());
        f.children().forEach(child -> assertTrue(f.pool().contains(child.txId())));
        assertEquals(4, f.pool().size());
    }

    @Test void directReplacementPreservesOtherGrandfatheredDescendants() {
        var f = fixture();
        var replacement = tx(List.of(point(f.parent(), 0)), 3, 10000);
        assertDoesNotThrow(() -> f.pool().admit(replacement, CONTEXT, COINS));
        assertFalse(f.pool().contains(f.children().getFirst().txId()));
        assertTrue(f.pool().contains(replacement.txId()));
        assertTrue(f.pool().contains(f.parent().txId()));
        for (var sibling : f.children().subList(1, 3)) assertTrue(f.pool().contains(sibling.txId()));
        assertEquals(4, f.pool().size());
        // This exception belongs to this replacement, not to the parent forever.
        var fresh = tx(List.of(point(f.parent(), 3)), 3, 10000);
        var error = assertThrows(MempoolAdmissionException.class, () -> f.pool().admit(fresh, CONTEXT, COINS));
        assertEquals("truc-topology", error.getMessage());
        assertEquals(4, f.pool().size());
    }

    @Test void unrelatedDirectConflictDoesNotWaiveParentDescendantLimit() {
        var f = fixture();
        var otherFund = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
        UtxoView coins = point -> point.equals(otherFund)
                ? Optional.of(new UtxoEntry(100000, SCRIPT, 100, false)) : COINS.find(point);
        var unrelated = tx(List.of(otherFund), 3, 90000);
        f.pool().admit(unrelated, CONTEXT, coins);
        var replacement = tx(List.of(point(f.parent(), 3), otherFund), 3, 10000);
        var error = assertThrows(MempoolAdmissionException.class, () -> f.pool().admit(replacement, CONTEXT, coins));
        assertEquals("truc-topology", error.getMessage());
        assertTrue(f.pool().contains(unrelated.txId()));
        f.children().forEach(child -> assertTrue(f.pool().contains(child.txId())));
        assertEquals(5, f.pool().size());
    }

    @Test void directConflictStillHasToPayReplacementFees() {
        var f = fixture();
        var weak = tx(List.of(point(f.parent(), 0)), 3, 18000);
        var error = assertThrows(MempoolAdmissionException.class, () -> f.pool().admit(weak, CONTEXT, COINS));
        assertEquals("replacement-fee", error.getMessage());
        f.children().forEach(child -> assertTrue(f.pool().contains(child.txId())));
        assertEquals(4, f.pool().size());
    }

    private record Fixture(Mempool pool, Transaction parent, List<Transaction> children) {}
    private static Fixture fixture() {
        var parent = tx(List.of(FUND), 3, 22000, 22000, 22000, 22000);
        UtxoView confirmed = point -> point.transactionId().equals(parent.txId()) && point.outputIndex().value() < 4
                ? Optional.of(new UtxoEntry(22000, SCRIPT, 100, false)) : Optional.empty();
        var pool = new Mempool();
        var children = new ArrayList<Transaction>();
        for (int i = 0; i < 3; i++) {
            var child = tx(List.of(point(parent, i)), 3, 17000);
            pool.admit(child, CONTEXT, confirmed);
            children.add(child);
        }
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(parent));
        assertEquals(4, pool.size());
        assertTrue(pool.contains(parent.txId()));
        return new Fixture(pool, parent, children);
    }
}

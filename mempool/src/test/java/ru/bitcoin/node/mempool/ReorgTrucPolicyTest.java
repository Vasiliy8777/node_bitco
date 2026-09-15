package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class ReorgTrucPolicyTest {
    @Test void disconnectedConflictMustPayForExistingTransactionAndDescendants() {
        var pool = new Mempool();
        var original = tx(List.of(FUND), 2, 90_000);
        var child = tx(List.of(point(original, 0)), 2, 80_000);
        pool.admit(original, CONTEXT, COINS);
        pool.admit(child, CONTEXT, COINS);
        var insufficient = tx(List.of(FUND), 2, 85_000);
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(insufficient));
        assertTrue(pool.contains(original.txId()));
        assertTrue(pool.contains(child.txId()));
        assertFalse(pool.contains(insufficient.txId()));
        var replacement = tx(List.of(FUND), 2, 70_000);
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(replacement));
        assertEquals(1, pool.size());
        assertTrue(pool.contains(replacement.txId()));
    }
    @Test void resurrectedParentMayChangeInheritanceWithoutBlockingIndependentAdmission() {
        var parent = tx(List.of(FUND), 2, 90_000);
        var child = tx(List.of(point(parent, 0)), 3, 80_000);
        UtxoView confirmedParent = point -> point.equals(point(parent, 0))
                ? Optional.of(new UtxoEntry(90_000, SCRIPT, 100, false)) : Optional.empty();
        var pool = new Mempool();
        pool.admit(child, CONTEXT, confirmedParent);
        pool.reconcile(CONTEXT, COINS, Set.of(), List.of(parent));
        assertTrue(pool.contains(parent.txId()));
        assertTrue(pool.contains(child.txId()));
        var other = new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0));
        UtxoView updated = point -> point.equals(other)
                ? Optional.of(new UtxoEntry(100_000, SCRIPT, 100, false)) : COINS.find(point);
        assertDoesNotThrow(() -> pool.admit(tx(List.of(other), 2, 90_000), CONTEXT, updated));
        assertEquals(3, pool.size());
        assertThrows(MempoolAdmissionException.class,
                () -> pool.admit(tx(List.of(point(child, 0)), 3, 70_000), CONTEXT, updated));
    }
    @Test void missingResurrectedParentRemovesItsChild() {
        var parent = tx(List.of(FUND), 2, 90_000);
        var child = tx(List.of(point(parent, 0)), 3, 80_000);
        var pool = new Mempool();
        pool.admit(child, CONTEXT, point -> Optional.of(new UtxoEntry(90_000, SCRIPT, 100, false)));
        pool.reconcile(CONTEXT, point -> Optional.empty(), Set.of(), List.of(parent));
        assertTrue(pool.isEmpty());
    }
}

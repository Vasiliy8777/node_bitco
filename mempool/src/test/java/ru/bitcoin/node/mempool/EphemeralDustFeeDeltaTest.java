package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class EphemeralDustFeeDeltaTest {
    @Test void negativeDeltaCannotHidePositiveBaseFeeOfDustParent() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 99999, 0);
        var child = tx(List.of(point(parent, 0), point(parent, 1)), 2, 90000);
        pool.prioritise(parent.txId(), -1);
        var before = pool.snapshot();
        var deltas = pool.prioritisedTransactions();
        var error = assertThrows(MempoolAdmissionException.class,
                () -> pool.admitPackage(List.of(parent, child), CONTEXT, COINS));
        assertEquals("dust-with-fee", error.getMessage());
        assertEquals(before, pool.snapshot());
        assertEquals(deltas, pool.prioritisedTransactions());
    }

    @Test void zeroBaseFeeDustStillRequiresZeroModifiedFee() {
        for (long delta : new long[]{-1, 1}) {
            var pool = new Mempool();
            var parent = tx(List.of(FUND), 2, 100000, 0);
            var child = tx(List.of(point(parent, 0), point(parent, 1)), 2, 90000);
            pool.prioritise(parent.txId(), delta);
            var before = pool.snapshot();
            var error = assertThrows(MempoolAdmissionException.class,
                    () -> pool.admitPackage(List.of(parent, child), CONTEXT, COINS));
            assertEquals("dust-with-fee", error.getMessage());
            assertEquals(before, pool.snapshot());
        }
    }

    @Test void zeroBaseAndModifiedFeeDustPackageRemainsAccepted() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 100000, 0);
        var child = tx(List.of(point(parent, 0), point(parent, 1)), 2, 90000);
        assertEquals(2, pool.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
        var entry = pool.find(parent.txId()).orElseThrow();
        assertEquals(0, entry.fee());
        assertEquals(0, entry.modifiedFee());
    }

    @Test void cancellationWithoutDustRemainsAccepted() {
        var pool = new Mempool();
        var parent = tx(List.of(FUND), 2, 99999);
        var child = tx(List.of(point(parent, 0)), 2, 90000);
        pool.prioritise(parent.txId(), -1);
        assertEquals(2, pool.admitPackage(List.of(parent, child), CONTEXT, COINS).size());
        var entry = pool.find(parent.txId()).orElseThrow();
        assertEquals(1, entry.fee());
        assertEquals(0, entry.modifiedFee());
    }
}

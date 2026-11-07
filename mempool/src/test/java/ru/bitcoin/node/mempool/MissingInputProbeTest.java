package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class MissingInputProbeTest {
    private static final OutPoint ABSENT = new OutPoint(Hash256.fromDisplayHex("33".repeat(32)), new UInt32(0));
    @Test void singleMissingInputUsesCoreReason() {
        var pool = new Mempool();
        var result = pool.testAccept(List.of(tx(List.of(ABSENT), 2, 1000)), CONTEXT, COINS);
        assertEquals("missing-inputs", result.rejectReason()); assertTrue(pool.isEmpty());
    }
    @Test void failurePositionDoesNotCommitEarlierPackageEntries() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        var child = tx(List.of(point(parent, 0)), 2, 80000);
        var missing = tx(List.of(ABSENT), 2, 1000);
        var packages = List.of(List.of(missing, parent), List.of(parent, missing), List.of(parent, child, missing));
        var indices = List.of(0, 1, 2);
        var before = pool.snapshot();
        for (int i = 0; i < packages.size(); i++) {
            var result = pool.testAccept(packages.get(i), CONTEXT, COINS);
            assertEquals("missing-inputs", result.rejectReason());
            assertEquals(indices.get(i).intValue(), result.failedTransactionIndex());
            assertEquals(before, pool.snapshot());
        }
    }
    @Test void nonexistentOutputOfKnownParentIsMissingInput() {
        var pool = new Mempool(); var parent = tx(List.of(FUND), 2, 90000);
        pool.admit(parent, CONTEXT, COINS); var before = pool.snapshot();
        var invalid = tx(List.of(point(parent, 42)), 2, 1000);
        assertEquals("missing-inputs", pool.testAccept(List.of(invalid), CONTEXT, COINS).rejectReason());
        assertEquals(before, pool.snapshot());
    }
}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class PackageStructuralRejectionTest {
    @Test void countPrecedesDuplicates() {
        reject(Collections.nCopies(26, tx(List.of(FUND), 2, 90000)), "package-too-many-transactions");
    }
    @Test void weightPrecedesDuplicates() {
        var large = new Transaction(2, tx(List.of(FUND), 2, 90000).inputs(),
                List.of(new TxOut(90000, new byte[51000])), new UInt32(0));
        reject(List.of(large, large), "package-too-large");
    }
    @Test void duplicatesPrecedeInputConflicts() {
        var parent = tx(List.of(FUND), 2, 90000);
        reject(List.of(parent, parent), "package-contains-duplicates");
    }
    @Test void sortingPrecedesEarlierInputConflict() {
        var parent = tx(List.of(FUND), 2, 90000);
        var conflict = tx(List.of(FUND), 2, 80000);
        var child = tx(List.of(point(parent, 0)), 2, 70000);
        reject(List.of(conflict, tx(List.of(FUND), 2, 85000), child, parent), "package-not-sorted");
    }
    @Test void sortedDoubleSpendIsPackageConflict() {
        reject(List.of(tx(List.of(FUND), 2, 90000), tx(List.of(FUND), 2, 80000)), "conflict-in-package");
    }
    @Test void transactionFailureIsNotPackageStructuralFailure() {
        assertFalse(new Mempool.TestAcceptResult(List.of(), "mempool-min-fee").packagePolicyFailure());
        assertFalse(new Mempool.TestAcceptResult(List.of(), null).packagePolicyFailure());
    }
    private static void reject(List<Transaction> transactions, String reason) {
        var pool = new Mempool();
        var result = pool.testAccept(transactions, CONTEXT, out -> { throw new AssertionError("UTXO read before structural rejection"); });
        assertEquals(reason, result.rejectReason());
        assertTrue(result.packagePolicyFailure());
        assertTrue(pool.isEmpty());
    }
}

package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MempoolContextTest {
    private static final OutPoint FUNDING = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
    private static final byte[] TRUE = java.util.HexFormat.of().parseHex("0020" + java.util.HexFormat.of().formatHex(ru.bitcoin.node.crypto.hash.Sha256.hash(new byte[]{0x51})));
    private static final UtxoView COINS = point -> point.equals(FUNDING)
            ? Optional.of(new UtxoEntry(100_000, TRUE, 100, false)) : Optional.empty();
    private static MempoolValidationContext context(long height, long time) {
        return new MempoolValidationContext(height, time, coinHeight -> 500_000_000);
    }

    @Test void absoluteHeightBoundaryAndFinalSequenceOverride() {
        var tx = tx(FUNDING, 0xffff_fffeL, 200, 90_000);
        assertThrows(TransactionValidationException.class, () -> new Mempool().admit(tx, context(200, 600_000_000), COINS));
        assertDoesNotThrow(() -> new Mempool().admit(tx, context(201, 600_000_000), COINS));
        assertDoesNotThrow(() -> new Mempool().admit(tx(FUNDING, 0xffff_ffffL, 999, 90_000), context(200, 600_000_000), COINS));
    }

    @Test void absoluteTimeUsesTipMtpWithStrictBoundary() {
        var tx = tx(FUNDING, 0xffff_fffeL, 600_000_000, 90_000);
        assertThrows(TransactionValidationException.class, () -> new Mempool().admit(tx, context(200, 600_000_000), COINS));
        assertDoesNotThrow(() -> new Mempool().admit(tx, context(200, 600_000_001), COINS));
    }

    @Test void relativeHeightBoundary() {
        var tx = tx(FUNDING, 2, 0, 90_000);
        assertThrows(MempoolAdmissionException.class, () -> new Mempool().admit(tx, context(101, 600_000_000), COINS));
        assertDoesNotThrow(() -> new Mempool().admit(tx, context(102, 600_000_000), COINS));
    }

    @Test void relativeTimeBoundaryAndDisabledSequence() {
        var tx = tx(FUNDING, (1L << 22) | 1, 0, 90_000);
        assertThrows(MempoolAdmissionException.class, () -> new Mempool().admit(tx, context(200, 500_000_511), COINS));
        assertDoesNotThrow(() -> new Mempool().admit(tx, context(200, 500_000_512), COINS));
        var noHistory = new MempoolValidationContext(200, 1, h -> { throw new AssertionError("Disabled sequence needs no MTP lookup"); });
        assertDoesNotThrow(() -> new Mempool().admit(tx(FUNDING, 0xffff_ffffL, 0, 90_000), noHistory, COINS));
    }

    @Test void conflictsAreRejectedAndEvictionReleasesInputs() {
        Mempool pool = new Mempool();
        var first = tx(FUNDING, 0xffff_ffffL, 0, 90_000);
        var conflict = tx(FUNDING, 0xffff_ffffL, 0, 90_001);
        pool.admit(first, context(200, 600_000_000), COINS);
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(conflict, context(200, 600_000_000), COINS));
        assertEquals(1, pool.size());
        pool.remove(first.txId());
        assertDoesNotThrow(() -> pool.admit(conflict, context(200, 600_000_000), COINS));
    }

    @Test void unconfirmedParentLocksAndDescendantEviction() {
        Mempool pool = new Mempool();
        var parent = tx(FUNDING, 0xffff_ffffL, 0, 90_000);
        var point = new OutPoint(parent.txId(), new UInt32(0));
        pool.admit(parent, context(200, 600_000_000), COINS);
        for (long sequence : new long[]{1, (1L << 22) | 1}) {
            assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(point, sequence, 0, 80_000), context(200, 600_000_000), COINS));
        }
        var child = tx(point, 0, 0, 80_000);
        pool.admit(child, context(200, 600_000_000), COINS);
        assertEquals(2, pool.size());
        pool.remove(parent.txId());
        assertTrue(pool.isEmpty());
        assertDoesNotThrow(() -> pool.admit(parent, context(200, 600_000_000), COINS));
        assertDoesNotThrow(() -> pool.admit(child, context(200, 600_000_000), COINS));
    }

    @Test void failedValidationDoesNotReserveInput() {
        Mempool pool = new Mempool();
        assertThrows(TransactionValidationException.class, () -> pool.admit(tx(FUNDING, 0xffff_fffeL, 999, 90_000), context(200, 600_000_000), COINS));
        assertDoesNotThrow(() -> pool.admit(tx(FUNDING, 0xffff_ffffL, 0, 90_000), context(200, 600_000_000), COINS));
    }

    @Test void concurrentConflictingAdmissionsHaveExactlyOneWinner() throws Exception {
        Mempool pool = new Mempool();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long value : new long[]{90_000, 90_001}) {
                results.add(workers.submit(() -> {
                    start.await();
                    try { pool.admit(tx(FUNDING, 0xffff_ffffL, 0, value), context(200, 600_000_000), COINS); return true; }
                    catch (MempoolAdmissionException e) { return false; }
                }));
            }
            start.countDown();
            assertNotEquals(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, pool.size());
    }

    @Test void revalidationRemovesNewlyNonFinalTransactionsAndReleasesConflicts() {
        Mempool pool = new Mempool();
        var transaction = tx(FUNDING, 0xffff_fffeL, 200, 90_000);
        pool.admit(transaction, context(201, 600_000_000), COINS);
        assertEquals(List.of(transaction.txId()), pool.revalidate(context(200, 600_000_000), COINS, Set.of()));
        assertTrue(pool.isEmpty());
        assertDoesNotThrow(() -> pool.admit(tx(FUNDING, 0xffff_ffffL, 0, 80_000), context(200, 600_000_000), COINS));
    }

    @Test void confirmedParentIsRemovedButChildRemainsWithChainFunding() {
        Mempool pool = new Mempool();
        var parent = tx(FUNDING, 0xffff_ffffL, 0, 90_000);
        var point = new OutPoint(parent.txId(), new UInt32(0));
        var child = tx(point, 0, 0, 80_000);
        pool.admit(parent, context(200, 600_000_000), COINS);
        pool.admit(child, context(200, 600_000_000), COINS);
        UtxoView newCoins = out -> out.equals(point) ? Optional.of(new UtxoEntry(90_000, TRUE, 200, false)) : Optional.empty();
        assertEquals(List.of(parent.txId()), pool.revalidate(context(201, 600_000_001), newCoins, Set.of(parent.txId())));
        assertEquals(1, pool.size());
        assertTrue(pool.contains(child.txId()));
    }

    @Test void historyFailureDoesNotPartiallyReplaceMempool() {
        Mempool pool = new Mempool();
        var transaction = tx(FUNDING, (1L << 22) | 1, 0, 90_000);
        pool.admit(transaction, context(200, 600_000_000), COINS);
        var broken = new MempoolValidationContext(200, 600_000_000, h -> { throw new IllegalStateException("Missing ancestor"); });
        assertThrows(IllegalStateException.class, () -> pool.revalidate(broken, COINS, Set.of()));
        assertTrue(pool.contains(transaction.txId()));
        assertThrows(MempoolAdmissionException.class, () -> pool.admit(tx(FUNDING, 0xffff_ffffL, 0, 90_001), context(200, 600_000_000), COINS));
    }

    private static Transaction tx(OutPoint point, long sequence, long lockTime, long value) {
        // Two outputs keep the transaction above the standard minimum stripped size.
        return new Transaction(2, List.of(new TxIn(point, new byte[0], new UInt32(sequence), new Witness(List.of(new byte[]{0x51})))),
                List.of(new TxOut(value, TRUE), new TxOut(0, new byte[]{0x6a})), new UInt32(lockTime));
    }
}
